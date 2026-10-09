package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.minestomconversion.extension.edit.BlockSection
import de.ionnetwork.minestomconversion.extension.edit.EditChanges
import de.ionnetwork.minestomconversion.extension.edit.EditClipboard
import de.ionnetwork.minestomconversion.extension.edit.EditProgress
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.minestomconversion.extension.edit.EditSelection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import net.kyori.adventure.text.Component
import net.minestom.server.instance.block.Block
import java.util.function.IntConsumer

/**
 * An EditTarget that records instead of writing.
 *
 * `runOnMapThread` runs inline so tests stay synchronous, unless `deferMapThread` is set; every other
 * method captures what it was asked to do so a test can assert on it. `rejection` makes `check`
 * refuse.
 *
 * The edit bracket is modelled, not faked away, because the real one has state a caller can corrupt.
 * `BuildEditTarget` (disassembled from the shipped `MinestomConversion.jar` with `javap -c`) keeps
 * three fields across `check`/`begin`/`stage`/`commit`/`abort`, and this mirrors all three:
 *
 *  - `staged` ([stagedBlocks]): `check` adds the slice size *before* testing the cap and does not
 *    roll it back when it rejects. Only `beginEdit`, `commitEdit` and `abortEdit` zero it. A guard
 *    that checks outside a bracket therefore leaks every refusal's block count into the next edit.
 *  - `pendingCount`: what `commitEdit` reports, i.e. the slices staged since `beginEdit` - not
 *    everything ever staged.
 *  - `pending` ([editOpen]): `stage` and `commitEdit` do nothing at all when no edit is open, so a
 *    misordered caller silently writes nothing rather than failing loudly.
 *
 * The real `check` tests the accumulator against a hardcoded 5,000,000 and the per-operation cap
 * against `maxBlocks()`; here both collapse onto [maxBlocks] so one constructor argument drives the
 * whole budget.
 */
class FakeEditTarget(
    minY: Int = -64,
    maxY: Int = 320,
    private val maxBlocks: Int = 1_000_000
) : EditTarget {

    // Named `minHeight`/`maxHeight`, not `minY`/`maxY`: a same-named `private val minY` alongside
    // `override fun minY(): Int` would be a needlessly confusing property/function pair to read
    // (verified to compile and resolve correctly - `minY` unqualified means the property, `minY()`
    // the function - but that is a trap for the next reader, not a design to keep). The constructor
    // parameters keep the brief's `minY`/`maxY` names so `FakeEditTarget(minY = -32, maxY = 200)`
    // still reads at call sites.
    private val minHeight = minY
    private val maxHeight = maxY

    private val blocks = HashMap<Triple<Int, Int, Int>, Block>()

    val staged = mutableListOf<EditChanges>()
    val commits = mutableListOf<Int>()
    val messages = mutableListOf<String>()
    val components = mutableListOf<Component>()
    val progress = mutableListOf<EditProgress>()

    /** Every `reportProgress` call in order, including the `null` that ends the display. */
    val progressCalls = mutableListOf<EditProgress?>()
    val loaded = mutableListOf<LongArray>()
    var rejection: EditRejection? = null
    var beginCount = 0
    var abortCount = 0
    var refuseToPlace: Block? = null

    /** Mirrors `BuildEditTarget.staged` - the per-edit block budget `check` spends. */
    var stagedBlocks = 0
        private set

    private var pendingCount = 0
    private var editOpen = false

    /** Makes [stage] throw, for exercising a guard's failure path. */
    var failOnStage: RuntimeException? = null

    /**
     * Holds [commitEdit]'s callback until [runDeferredCommits], the way the real one does: it hands
     * the batch to `EditEngine.applyPrebuilt`, which runs it on the instance scheduler's next tick,
     * so nothing a caller wants from that callback is available when `commitEdit` returns.
     */
    var deferCommitCallback = false
    private val deferredCommits = mutableListOf<Pair<IntConsumer, Int>>()

    fun runDeferredCommits() {
        val due = deferredCommits.toList()
        deferredCommits.clear()
        due.forEach { (callback, applied) -> callback.accept(applied) }
    }

    fun set(x: Int, y: Int, z: Int, block: Block) { blocks[Triple(x, y, z)] = block }

    /**
     * Inline by default so tests stay synchronous, deferred when [deferMapThread] is set.
     *
     * Deferring is what the real one always does - `BuildEditTarget.runOnMapThread` is
     * `instance.scheduler().scheduleNextTick(work)` (disassembled from the shipped
     * `MinestomConversion.jar` with `javap -c`) - so a test that needs to see whether a caller
     * depends on the work having already run has to be able to hold it back.
     */
    override fun runOnMapThread(runnable: Runnable) {
        if (deferMapThread) mapThreadWork += runnable else runnable.run()
    }

    /** Makes [runOnMapThread] queue its work instead of running it, the way the real one does. */
    var deferMapThread = false
    private val mapThreadWork = mutableListOf<Runnable>()

    /** Runs whatever [runOnMapThread] queued, i.e. lets the map tick. */
    fun runDeferredMapThread() {
        val due = mapThreadWork.toList()
        mapThreadWork.clear()
        due.forEach(Runnable::run)
    }

    override fun ensureLoaded(chunks: LongArray) { loaded += chunks }

    /**
     * Everything [ensureLoaded] was asked for, as chunk coordinates, decoded exactly the way
     * `BuildEditTarget.ensureLoaded` decodes them - `(key ushr 32).toInt()` and `key.toInt()` - so a
     * test asserting on these is also asserting the caller packed them the way the SPI documents.
     */
    fun loadedChunks(): Set<Pair<Int, Int>> =
        loaded.flatMap { keys -> keys.map { (it ushr 32).toInt() to it.toInt() } }.toSet()
    override fun mapId(): String = "test-map"
    override fun minY(): Int = minHeight
    override fun maxY(): Int = maxHeight
    override fun maxBlocks(): Int = maxBlocks
    override fun blockAt(x: Int, y: Int, z: Int): Block = blocks[Triple(x, y, z)] ?: Block.AIR

    override fun snapshotSection(sectionX: Int, sectionY: Int, sectionZ: Int): BlockSection {
        val out = arrayOfNulls<Block>(BlockSection.BLOCK_COUNT)
        var i = 0
        for (y in 0 until BlockSection.SIZE) for (z in 0 until BlockSection.SIZE) for (x in 0 until BlockSection.SIZE) {
            out[i++] = blockAt(sectionX * 16 + x, sectionY * 16 + y, sectionZ * 16 + z)
        }
        @Suppress("UNCHECKED_CAST")
        return BlockSection(sectionX, sectionY, sectionZ, out as Array<Block>)
    }

    override fun check(changes: EditChanges): EditRejection? {
        if (changes.size() == 0) return null
        stagedBlocks += changes.size()
        if (stagedBlocks > maxBlocks) {
            return EditRejection(
                "buildserver.edit.reject.too_large",
                mapOf("count" to stagedBlocks.toString(), "max" to maxBlocks.toString())
            )
        }
        return rejection
    }

    override fun beginEdit() {
        beginCount++
        editOpen = true
        pendingCount = 0
        stagedBlocks = 0
    }

    override fun stage(changes: EditChanges) {
        failOnStage?.let { throw it }
        if (!editOpen) return
        staged += changes
        pendingCount += changes.size()
    }

    override fun commitEdit(onDone: IntConsumer) {
        if (!editOpen) return
        val applied = pendingCount
        editOpen = false
        pendingCount = 0
        stagedBlocks = 0
        commits += applied
        if (deferCommitCallback) deferredCommits += onDone to applied else onDone.accept(applied)
    }

    override fun abortEdit() {
        abortCount++
        editOpen = false
        pendingCount = 0
        stagedBlocks = 0
    }
    override fun selection(): EditSelection = EditSelection(0, 0, 0, 0, 0, 0)
    override fun setSelection(selection: EditSelection) = Unit
    override fun clipboard(): EditClipboard = EditClipboard(0, 0, 0, 0, 0, 0, emptyArray())
    override fun setClipboard(clipboard: EditClipboard) = Unit
    override fun canEverPlace(block: Block): Boolean = block != refuseToPlace
    override fun sendPrefixed(component: Component) { components += component }
    /**
     * The parameter is nullable because the SPI's is: `reportProgress(null)` is how a caller clears
     * the display, and a non-null override would turn that into a Kotlin null-check failure here
     * while the real target took it perfectly well.
     */
    override fun reportProgress(editProgress: EditProgress?) {
        progressCalls += editProgress
        if (editProgress != null) progress += editProgress
    }
    override fun message(key: String, args: Map<String, String>) { messages += key }
}
