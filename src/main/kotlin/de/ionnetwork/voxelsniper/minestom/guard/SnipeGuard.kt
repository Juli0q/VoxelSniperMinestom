package de.ionnetwork.voxelsniper.minestom.guard

import com.github.kevindagame.util.brushOperation.BlockOperation
import com.github.kevindagame.util.brushOperation.BrushOperation
import com.github.kevindagame.voxelsniper.events.player.PlayerSnipeEvent
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.minestomconversion.extension.edit.EditProgress
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts the build server's rules in front of every block a brush writes.
 *
 * VoxelSniper hands over the complete change set before anything moves - `AbstractBrush` builds a
 * `List<BrushOperation>`, fires [PlayerSnipeEvent], and only then walks the list writing blocks. That
 * is exactly the shape `EditTarget.check` wants, so this guard needs no buffering of its own: it
 * converts, asks, applies as one batch, and cancels the operations it applied.
 *
 * Contrast WorldEditMinestom, which has to buffer an entire edit at ~400 bytes per block just to
 * make a refusal write nothing, and install an extent above WorldEdit's counters just to make a
 * refusal report zero. Both properties are free here.
 *
 * Threading: [guard] runs on its caller's thread and must therefore be called on the map thread,
 * because `EditTarget.stage` is map-thread-only. It deliberately does not wrap itself in
 * `EditTarget.runOnMapThread`: the real implementation of that always defers to the next tick, so
 * the guard would return before it had cancelled anything and core's write loop would run the
 * unguarded blocks in the meantime. Getting the snipe onto the map thread is the listener's job.
 */
object SnipeGuard {

    sealed interface Outcome {
        /**
         * The change set passed and was handed to the build server as one batch.
         *
         * [blocks] is the size of that change set, not a count of blocks confirmed written:
         * `commitEdit` applies the batch on the instance scheduler's next tick and only calls back
         * then, so no confirmed number exists yet when this returns.
         */
        data class Applied(val blocks: Int) : Outcome

        /** The build server refused; nothing was written. */
        data class Rejected(val rejection: EditRejection) : Outcome

        /** The brush emitted no block writes - only biome, sign or entity operations. */
        data object NothingToDo : Outcome
    }

    /** Registered once ever; `HandlerList` has no way to take a listener back off. */
    private val registered = AtomicBoolean(false)

    /** Whether this guard is currently in force. See [uninstall]. */
    @Volatile
    private var active = false

    /**
     * Puts the guard in front of every snipe. Safe to call twice; the second call changes nothing.
     *
     * Idempotent because it has to be: `PlayerSnipeEvent`'s `HandlerList` (VoxelSniperCore 8.14.0)
     * offers `registerListener` and no way to unregister, so a second registration would leave two
     * guards on one static list. Each snipe would then be bracketed, checked, staged and committed
     * twice - the same blocks written as two batches, and twice the per-edit block budget spent on
     * one edit.
     */
    fun install() {
        if (registered.compareAndSet(false, true)) PlayerSnipeEvent.registerListener(::onSnipe)
        active = true
    }

    /**
     * Stands the guard down, for shutdown.
     *
     * The listener itself cannot be removed, so this flips it to refusing instead of removing it.
     * Refusing rather than passing through: an extension that has shut down has no rules left to
     * apply, and "there is no guard" is the one situation in which an unguarded write must least of
     * all be attempted.
     */
    fun uninstall() {
        active = false
    }

    /**
     * Resolves the map this player may edit and runs their snipe through it.
     *
     * The guard is fetched per snipe rather than held from initialization, as `EditGuards` requires:
     * a session's map, profile and role all change during a visit, and a target captured early would
     * be a target for a session that did not exist yet.
     */
    internal fun onSnipe(event: PlayerSnipeEvent) {
        // Shut down, but still on core's static handler list - see uninstall().
        if (!active) return cancelBlockWrites(event.operations)

        val uuid = event.player.uniqueId
        // Null on a server that is not a build node: no rules to apply and no map to apply them to.
        // The block writes are still cancelled rather than left to core, because "there is no guard"
        // is the one situation in which an unguarded write must least of all be attempted.
        val guard = EditGuards.get()
        if (guard == null) return cancelBlockWrites(event.operations)
        val target = guard.targetFor(uuid)
        if (target == null) {
            guard.message(uuid, guard.noTargetReason(uuid), emptyMap())
            event.isCancelled = true
            return
        }
        guard(target, event.operations, event.brush.name)
    }

    /**
     * Applies the block half of [operations] through [target] and cancels it, leaving everything
     * else for core to execute.
     *
     * Cancelling individual operations rather than the event is what lets biome, sign and entity
     * operations still run (spec section 6). It also gives the block-only case for free: with every
     * operation cancelled, `PlayerSnipeEvent.isCancelled` becomes true on its own, so core skips its
     * own undo bookkeeping and chunk reload without being told to.
     *
     * Every path is bracketed by `beginEdit`/`commitEdit`-or-`abortEdit`, including the refusal and
     * the exception. That is not tidiness: `BuildEditTarget.check` adds the slice size to the target's
     * per-edit block counter *before* testing it against the cap and does not roll it back when it
     * rejects, and only `beginEdit`, `commitEdit` and `abortEdit` zero that counter. This is a
     * contract guarantee, not a live-outage fix: `BuildEditGuard.targetFor` constructs a fresh
     * `BuildEditTarget` on every call, and [onSnipe] resolves the target per snipe, so the counter
     * cannot in fact survive from one refused snipe to the next on today's adapter. But nothing in
     * `EditTarget`'s contract says a caller may not hold a target across several checks, and a caller
     * that does would drift this counter toward a false permanent refusal without this bracket. The
     * bracket is free, so it stays regardless of whether this call site happens to be exposed.
     */
    internal fun guard(target: EditTarget, operations: List<BrushOperation>, brushName: String): Outcome {
        // Safe to leave every other operation kind unguarded only because
        // MinestomBlockState.update(force, applyPhysics) is a no-op today - see its kdoc. If that
        // ever starts writing, block-entity state changes reach core's path with no rule in front
        // of them, and nothing here would fail to flag it.
        val blockOps = operations.filterIsInstance<BlockOperation>()
        if (blockOps.isEmpty()) return Outcome.NothingToDo

        val startedAt = System.currentTimeMillis()
        try {
            val changes = SnipeChanges(blockOps)
            // Block operations that produced no writes: every one of them carried block data this
            // adapter did not create, which should not be reachable - `MinestomBlockData` is the only
            // `IBlockData` here. They cannot be described to `check`, so the `finally` below cancels
            // them; the one thing that must not happen is core writing them with no rule in front.
            if (changes.isEmpty()) return Outcome.NothingToDo

            val submitted = changes.size()

            // Opens the bracket *before* the question is asked, because asking is what spends the
            // budget.
            target.beginEdit()

            val rejection = target.check(changes)
            if (rejection != null) {
                target.abortEdit()
                target.message(rejection)
                return Outcome.Rejected(rejection)
            }

            // After the check, so a refused edit never loads a chunk for a write it will not make,
            // and before the stage, because a batch applied to an unloaded chunk is not applied at
            // all - Minestom logs a warning and skips it, and the blocks are simply gone while the
            // outcome and the progress report both still claim them.
            target.ensureLoaded(chunkKeysCovering(changes))

            target.stage(changes)
            target.commitEdit { applied ->
                // Runs on the map's next tick, off this call stack.
                target.reportProgress(
                    EditProgress(brushName, applied.toLong(), System.currentTimeMillis() - startedAt, submitted.toLong())
                )
                // The null is what takes the edit off the builder's action bar. A non-null report
                // only stores progress and drops any pending linger; nothing else on the server ever
                // clears it, so without this a builder's first snipe pins "ball 100%" there for the
                // rest of their session. Reporting the honest total first is still what makes this
                // clear linger for a second rather than blink out.
                target.reportProgress(null)
            }
            return Outcome.Applied(submitted)
        } catch (failure: Throwable) {
            // An edit left open would hold this session's batch for the rest of the session, and the
            // dirty counter would shrink every later edit's budget.
            target.abortEdit()
            throw failure
        } finally {
            // Cancelled on every exit, including the refusal and the failure: a block this guard did
            // not write must not fall through to core's write loop, which would place it with no rule
            // in front of it at all.
            cancelBlockWrites(blockOps)
        }
    }

    private fun cancelBlockWrites(operations: List<BrushOperation>) {
        operations.forEach { if (it is BlockOperation) it.isCancelled = true }
    }

    /**
     * Every chunk the change set's bounding box covers, in the packing `EditTarget.ensureLoaded`
     * documents: `(chunkX << 32) | (chunkZ & 0xFFFFFFFF)`.
     *
     * The whole box rather than only the columns actually written, because loading a chunk the edit
     * turns out not to touch costs a load and loading none of them costs the writes. `shr 4` rather
     * than `/ 16` because a build map's coordinates are routinely negative and only the arithmetic
     * shift floors.
     */
    private fun chunkKeysCovering(changes: SnipeChanges): LongArray {
        val minChunkX = changes.minX() shr 4
        val maxChunkX = changes.maxX() shr 4
        val minChunkZ = changes.minZ() shr 4
        val maxChunkZ = changes.maxZ() shr 4
        // Counted in Long first: the Int product below would silently overflow to a negative or
        // wrapped-small size for a pathological box, throwing NegativeArraySizeException or letting
        // the fill loop below run past the array it got. Unreachable through any current brush -
        // this is a guard against a box no brush can build today, not a redesign.
        val chunkCount = (maxChunkX - minChunkX + 1).toLong() * (maxChunkZ - minChunkZ + 1).toLong()
        require(chunkCount in 1..Int.MAX_VALUE.toLong()) {
            "change set spans $chunkCount chunks, which is not a loadable prefetch size"
        }
        val keys = LongArray(chunkCount.toInt())
        var i = 0
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                keys[i++] = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
            }
        }
        return keys
    }
}
