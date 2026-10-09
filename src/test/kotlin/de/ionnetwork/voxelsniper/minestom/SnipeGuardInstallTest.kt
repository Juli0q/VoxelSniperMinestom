package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.brush.IBrush
import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.snipe.SnipeAction
import com.github.kevindagame.snipe.SnipeData
import com.github.kevindagame.util.VoxelMessage
import com.github.kevindagame.util.brushOperation.BlockOperation
import com.github.kevindagame.voxelsniper.block.IBlock
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.events.player.PlayerSnipeEvent
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.vector.VoxelVector
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.kyori.adventure.text.Component
import net.minestom.server.instance.block.Block
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PlayerSnipeEvent`'s HandlerList (VoxelSniperCore 8.14.0) has `registerListener` and no way to
 * unregister, so a second `install()` would leave two guards on the same static list: every snipe
 * would be checked, staged and committed twice - two batches of the same blocks, and twice the
 * per-edit block budget spent.
 */
class SnipeGuardInstallTest {

    private val world = MinestomWorld(FakeEditTarget())
    private val player = FakeSniperPlayer(UUID.randomUUID())
    private val target = FakeEditTarget()
    private val guard = FakeEditGuard()

    @BeforeTest
    fun register() {
        guard.targets[player.uniqueId] = target
        EditGuards.register(guard)
    }

    @AfterTest
    fun deregister() {
        SnipeGuard.uninstall()
        EditGuards.register(null)
    }

    private fun blockOp(x: Int) = BlockOperation(
        BaseLocation(world, x.toDouble(), 64.0, 0.0),
        MinestomBlockData(Block.AIR),
        MinestomBlockData(Block.STONE)
    )

    @Test
    fun `installing twice guards a snipe once, not twice`() {
        SnipeGuard.install()
        SnipeGuard.install()

        PlayerSnipeEvent(player, FakeBrush(), listOf(blockOp(0), blockOp(1))).callEvent()

        assertEquals(1, target.beginCount, "a second registration would bracket the same snipe twice")
        assertEquals(listOf(2), target.commits, "a second registration would commit the same blocks twice")
    }

    @Test
    fun `an uninstalled guard writes nothing rather than letting core write unguarded`() {
        SnipeGuard.install()
        SnipeGuard.uninstall()
        val operations = listOf(blockOp(0), blockOp(1))

        PlayerSnipeEvent(player, FakeBrush(), operations).callEvent()

        assertEquals(0, target.beginCount)
        assertTrue(operations.all { it.isCancelled }, "core's write loop must not run what no guard checked")
    }

    /**
     * `EditGuards.get()` answers null on a server that is not a build node - there is no rule to
     * apply and no map to apply it to, so `onSnipe` cancels rather than letting core write with no
     * guard in front of it at all. Asserted through `event.isCancelled`, the same thing
     * `AbstractBrush.performOperations` checks to skip its whole write loop (verified in
     * `AbstractBrush.kt:90` of VoxelSniperCore 8.14.0), not through which mechanism produced it.
     */
    @Test
    fun `with no build guard registered a snipe writes nothing`() {
        SnipeGuard.install()
        EditGuards.register(null)
        val event = PlayerSnipeEvent(player, FakeBrush(), listOf(blockOp(0), blockOp(1)))

        event.callEvent()

        assertEquals(0, target.beginCount, "no guard means nothing was ever checked against a target")
        assertTrue(event.isCancelled, "core's write loop must be suppressed with no guard to ask")
    }

    /**
     * `guard.targetFor` answers null for a session with no editable map right now - a reviewer who
     * has not left read-only, for instance. `onSnipe` cancels the event directly here rather than
     * per operation (contrast the guard==null branch above), so this test asserts the same
     * observable outcome - `event.isCancelled` - rather than the mechanism, per the same reasoning.
     */
    @Test
    fun `with no target for this player a snipe writes nothing`() {
        guard.targets[player.uniqueId] = null
        SnipeGuard.install()
        val event = PlayerSnipeEvent(player, FakeBrush(), listOf(blockOp(0), blockOp(1)))

        event.callEvent()

        assertEquals(0, target.beginCount, "no target means nothing was ever checked or written")
        assertTrue(event.isCancelled, "core's write loop must be suppressed with no map to edit")
    }
}

/** Only `name` is ever read by the guard; everything else throws so a wider use fails loudly. */
private class FakeBrush : IBrush {
    override var name: String = "ball"
    override var permissionNode: String = "voxelsniper.brush.ball"
    override fun info(vm: VoxelMessage) = throw NotImplementedError()
    override fun parseParameters(triggerHandle: String, params: Array<String>, v: SnipeData) =
        throw NotImplementedError()
    override fun perform(action: SnipeAction, data: SnipeData, targetBlock: IBlock, lastBlock: IBlock): Boolean =
        throw NotImplementedError()
    override fun registerArguments(): List<String> = throw NotImplementedError()
    override fun registerArgumentValues(): HashMap<String, List<String>> = throw NotImplementedError()
}

/** The guard reads a uuid off the event's player and nothing else. */
private class FakeSniperPlayer(private val uuid: UUID) : IPlayer {
    override fun getUniqueId(): UUID = uuid
    override fun getName(): String = "builder"
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
    override fun getLocation(): BaseLocation = notModelled()
    override fun getEyeLocation(): BaseLocation = notModelled()
    override fun getWorld(): IWorld = notModelled()
    override fun addPassenger(entity: IEntity) = notModelled()
    override fun getPassengers(): List<IEntity> = notModelled()
    override fun getNearbyEntities(x: Int, y: Int, z: Int): List<IEntity> = notModelled()
    override fun eject() = notModelled()
    override fun teleport(other: IEntity): Boolean = notModelled()

    private fun notModelled(): Nothing =
        throw NotImplementedError("FakeSniperPlayer models a uuid and nothing else")
}
