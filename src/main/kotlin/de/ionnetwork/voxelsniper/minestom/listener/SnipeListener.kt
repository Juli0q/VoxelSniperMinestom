package de.ionnetwork.voxelsniper.minestom.listener

import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.voxelsniper.block.BlockFace
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers
import de.ionnetwork.voxelsniper.minestom.guard.ChunkPrefetch
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.Event
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Turns a builder's clicks into snipes, on the thread that owns the map they are editing.
 *
 * **The whole `Sniper.snipe` call runs inside `runOnMapThread`, not just the write.** Brush
 * computation reads the map as it goes - ErodeBrush and OverlayBrush read neighbours per position,
 * and every brush ray-traces for its target block first - and `EditTarget` says plainly that reads
 * and writes both belong on the map's own thread because "Minestom chunk access is not thread-safe,
 * so an edit built or applied anywhere else is racing the tick".
 *
 * The dispatch has to happen here rather than inside
 * [SnipeGuard][de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard]: the real `runOnMapThread` is
 * `instance.scheduler().scheduleNextTick(work)` (`BuildEditTarget`, read from the shipped
 * `MinestomConversion.jar` with `javap -c`), so it always defers by a tick. A guard that wrapped
 * itself in it would return *before* it had cancelled a single operation, and core's own write loop -
 * which runs the moment the snipe event returns - would write the whole change set unguarded.
 */
object SnipeListener {

    /**
     * Blocks of slack around the builder, on top of their brush size.
     *
     * The brush acts on the block it ray-traced to, not on the builder, so the prefetched square has
     * to be wide enough to hold that block plus the brush's own reach. One chunk of slack covers a
     * builder aiming at their feet or just past them, which is what a brush is normally used for.
     * [ChunkPrefetch.keysFor] documents what this does not cover, and why widening it would cost more
     * than it saves.
     */
    internal const val PREFETCH_MARGIN = 16

    private val log: Logger = LoggerFactory.getLogger(SnipeListener::class.java)

    private val listeners = mutableListOf<EventListener<out Event>>()
    private var installedOn: EventNode<Event>? = null

    fun install() = install(MinecraftServer.getGlobalEventHandler())

    /**
     * Registers the four listeners on [node]. Safe to call twice; the second call changes nothing.
     *
     * Idempotent because a second set of listeners would dispatch a second snipe for the same click:
     * two brush passes, two change sets, two commits, and a builder's `//undo` reversing half of what
     * they just did.
     */
    internal fun install(node: EventNode<Event>) {
        if (installedOn != null) return
        installedOn = node

        // A player entry per connection is what owns their Sniper - brush, brush size, performer and
        // tool assignments all live there. Created for everyone who spawns rather than only for
        // players who have a map right now: a builder whose session starts after they spawn (or who
        // is moved onto a map by a BuildSwap rotation) must still have somewhere for their brush to
        // live, and the world they get resolves its map per read, so it follows them.
        add(node, EventListener.of(PlayerSpawnEvent::class.java) { event ->
            MinestomPlayers.join(event.player, MinestomWorld(event.player.uuid))
        })
        add(node, EventListener.of(PlayerDisconnectEvent::class.java) { event ->
            MinestomPlayers.leave(event.player.uuid)
        })
        // Right-clicking a block. Minestom's BlockPlacementListener fires this one; it fires
        // PlayerUseItemEvent only for a right click that hit nothing, so a single click never
        // arrives twice.
        add(node, EventListener.of(PlayerBlockInteractEvent::class.java) { event ->
            if (event.hand != PlayerHand.MAIN) return@of
            dispatch(event.player.uuid, Sniper.Action.RIGHT_CLICK_BLOCK)
        })
        // Right-clicking air, which is how a builder snipes anything further away than their reach -
        // the client sends a use-item packet when it has no block to place against.
        add(node, EventListener.of(PlayerUseItemEvent::class.java) { event ->
            if (event.hand != PlayerHand.MAIN) return@of
            dispatch(event.player.uuid, Sniper.Action.RIGHT_CLICK_AIR)
        })
    }

    fun uninstall() {
        val node = installedOn ?: return
        listeners.forEach { node.removeListener(it) }
        listeners.clear()
        installedOn = null
    }

    /** Test seam: how many listeners are currently registered, i.e. whether a second install added any. */
    internal fun listenerCount(): Int = listeners.size

    private fun add(node: EventNode<Event>, listener: EventListener<out Event>) {
        node.addListener(listener)
        listeners += listener
    }

    /**
     * One click, from a Minestom thread.
     *
     * The tool check comes first and costs nothing: `Sniper.snipe` refuses immediately for an item
     * with no action assigned to it, so without this every right click by every player on the server
     * would resolve a map and load a square of chunks for a snipe that was never going to happen.
     */
    private fun dispatch(uuid: UUID, action: Sniper.Action) {
        val player = MinestomPlayers.byUuid(uuid) ?: return
        val sniper = player.sniper
        // `/vs disable`. `Sniper.snipe` does not test this itself - every platform listener does,
        // SpigotVoxelSniperListener included - so without it the command would do nothing at all.
        if (!sniper.isEnabled) return
        val held = player.itemInHand ?: return
        val tool = sniper.getSnipeTool(sniper.getToolId(held)) ?: return
        if (!tool.hasToolAssigned(held)) return

        dispatchOnMapThread(player, tool.snipeData.brushSize) {
            // Read again on the map thread, deliberately: this is the snipe core will act on, and
            // the item is what decides which tool and which action it uses.
            sniper.snipe(action, player.itemInHand, null, BlockFace.SELF)
        }
    }

    /**
     * Loads what the brush will read, then hands [snipe] to the map's own thread.
     *
     * [snipe] is a parameter so a test can watch *when* it runs without standing up core's brush
     * manager; [dispatch] supplies the real one.
     *
     * The target resolved here is used for the prefetch and the hand-off only. `SnipeGuard` resolves
     * its own when the snipe event fires a tick later, which is the resolution that decides where the
     * blocks land - and by then the session may have moved, which is exactly why the SPI forbids
     * holding one.
     */
    internal fun dispatchOnMapThread(player: IPlayer, brushSize: Int, snipe: () -> Unit) {
        val uuid = player.uniqueId
        // Null on a server that is not a build node: nothing to edit and nothing to say about it.
        val guard = EditGuards.get() ?: return
        val target = guard.targetFor(uuid)
        if (target == null) {
            // The same refusal every other build-server command gives, rather than a click that
            // silently does nothing.
            guard.message(uuid, guard.noTargetReason(uuid), emptyMap())
            return
        }

        // Before the hand-off, not inside it: `ensureLoaded` blocks on `CompletableFuture.allOf(..)
        // .join()` until every chunk is in, and the map thread is the one thread that cannot afford
        // to wait for its own chunk loads. The SPI says the same - "safe to call from a worker
        // thread, and expected to be - the waiting belongs there".
        val at = player.location
        target.ensureLoaded(ChunkPrefetch.keysFor(at.blockX, at.blockZ, brushSize + PREFETCH_MARGIN))

        target.runOnMapThread {
            try {
                snipe()
            } catch (failure: Throwable) {
                // Minestom's scheduler routes this to its ExceptionManager, which reports it as an
                // anonymous server error. Naming the player and the extension here is the difference
                // between a diagnosable report and a stack trace nobody can place. Nothing is left
                // half-written: the guard's own `finally` aborts the edit and cancels every block
                // operation before this sees the failure.
                log.error("VoxelSniper snipe failed for {} ({})", player.name, uuid, failure)
            }
        }
    }
}
