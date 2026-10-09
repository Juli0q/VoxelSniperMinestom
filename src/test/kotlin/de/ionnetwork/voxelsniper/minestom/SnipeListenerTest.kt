package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.vector.VoxelVector
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.guard.ChunkPrefetch
import de.ionnetwork.voxelsniper.minestom.listener.SnipeListener
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.kyori.adventure.text.Component
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnipeListenerTest {

    private val guard = FakeEditGuard()
    private val player = FakeSnipePlayer(UUID.randomUUID(), "builder", x = 8.0, z = 8.0)

    @BeforeTest
    fun register() = EditGuards.register(guard)

    @AfterTest
    fun deregister() {
        SnipeListener.uninstall()
        EditGuards.register(null)
    }

    /**
     * The one thing `SnipeGuard.guard` cannot do for itself. The real `runOnMapThread` is
     * `instance.scheduler().scheduleNextTick(work)` (read from `BuildEditTarget` in the shipped
     * `MinestomConversion.jar` with `javap -c`), so it *always* defers; a snipe that ran on the
     * listener's thread would read and write the map off its own tick.
     */
    @Test
    fun `the snipe runs inside runOnMapThread, never before it`() {
        val target = FakeEditTarget().apply { deferMapThread = true }
        guard.targets[player.uniqueId] = target
        var sniped = false

        SnipeListener.dispatchOnMapThread(player, brushSize = 3) { sniped = true }

        assertFalse(sniped, "the snipe must not run on the listener's thread")
        target.runDeferredMapThread()
        assertTrue(sniped, "the snipe must run once the map thread picks the work up")
    }

    @Test
    fun `the chunks the brush will read are loaded before the map thread is asked for anything`() {
        val target = FakeEditTarget().apply { deferMapThread = true }
        guard.targets[player.uniqueId] = target

        SnipeListener.dispatchOnMapThread(player, brushSize = 3) { }

        // Centred on the player, so it covers what the brush reads before it has any operation list.
        val expected = ChunkPrefetch
            .keysFor(8, 8, 3 + SnipeListener.PREFETCH_MARGIN)
            .map { (it ushr 32).toInt() to it.toInt() }
            .toSet()
        assertEquals(expected, target.loadedChunks())
    }

    @Test
    fun `a player with no editable session is told why, and nothing is dispatched`() {
        guard.targets[player.uniqueId] = null
        var sniped = false

        SnipeListener.dispatchOnMapThread(player, brushSize = 3) { sniped = true }

        assertFalse(sniped)
        assertEquals(listOf(player.uniqueId to guard.reason), guard.messages)
    }

    @Test
    fun `nothing is dispatched on a server that is not a build node`() {
        EditGuards.register(null)
        var sniped = false

        SnipeListener.dispatchOnMapThread(player, brushSize = 3) { sniped = true }

        assertFalse(sniped)
    }

    /**
     * A brush that throws - an unloaded chunk read, a bug - must not take the caller with it. The
     * guard's own `finally` has already cancelled the operations and aborted the edit by then, so
     * there is nothing left to unwind; what is left is telling the log which player it was.
     */
    @Test
    fun `a failing snipe is contained rather than thrown at the map thread`() {
        val target = FakeEditTarget()
        guard.targets[player.uniqueId] = target

        SnipeListener.dispatchOnMapThread(player, brushSize = 3) { error("brush exploded") }
    }

    @Test
    fun `installing twice registers one set of listeners, not two`() {
        val node = EventNode.all("snipe-listener-test")

        SnipeListener.install(node)
        val once = SnipeListener.listenerCount()
        SnipeListener.install(node)

        assertTrue(once > 0, "install must register something")
        assertEquals(once, SnipeListener.listenerCount(), "a second install would double-commit every snipe")
    }

    @Test
    fun `uninstall removes the listeners and allows a later reinstall`() {
        val node = EventNode.all("snipe-listener-test")

        SnipeListener.install(node)
        SnipeListener.uninstall()
        assertEquals(0, SnipeListener.listenerCount())

        SnipeListener.install(node)
        assertTrue(SnipeListener.listenerCount() > 0)
    }

    @Test
    fun `uninstalling without installing is a no-op`() {
        SnipeListener.uninstall()
        assertEquals(0, SnipeListener.listenerCount())
    }
}

/**
 * A builder, reduced to what the dispatch path reads: a uuid, a name and where they stand.
 *
 * Everything else throws rather than returning a plausible lie - in particular [getSniper], because
 * a real `Sniper` needs `VoxelBrushManager` and a loaded platform, and a test that quietly got one
 * would be testing core rather than the dispatch.
 */
private class FakeSnipePlayer(
    private val uuid: UUID,
    private val name: String,
    private val x: Double = 0.0,
    private val y: Double = 64.0,
    private val z: Double = 0.0
) : IPlayer {

    private val world = MinestomWorld(FakeEditTarget())

    override fun getUniqueId(): UUID = uuid
    override fun getName(): String = name
    override fun getLocation(): BaseLocation = BaseLocation(world, x, y, z)
    override fun getEyeLocation(): BaseLocation = BaseLocation(world, x, y + 1.62, z)
    override fun getWorld(): IWorld = world

    override fun getSniper(): Sniper = notModelled()
    override fun sendMessage(message: String) = notModelled()
    override fun sendMessage(message: Component) = notModelled()
    override fun hasPermission(permissionNode: String): Boolean = notModelled()
    override fun isSneaking(): Boolean = notModelled()
    override fun teleport(location: BaseLocation) = notModelled()
    override fun launchProjectile(type: VoxelEntityType, velocity: VoxelVector): IEntity = notModelled()
    override fun getItemInHand(): VoxelMaterial = notModelled()
    override fun getType(): VoxelEntityType = notModelled()
    override fun remove() = notModelled()
    override fun getEntityId(): Int = notModelled()
    override fun addPassenger(entity: IEntity) = notModelled()
    override fun getPassengers(): List<IEntity> = notModelled()
    override fun getNearbyEntities(x: Int, y: Int, z: Int): List<IEntity> = notModelled()
    override fun eject() = notModelled()
    override fun teleport(other: IEntity): Boolean = notModelled()

    private fun notModelled(): Nothing =
        throw NotImplementedError("FakeSnipePlayer models identity and position and nothing else")
}
