package de.ionnetwork.voxelsniper.minestom.command

import com.github.kevindagame.command.VoxelCommand
import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.vector.VoxelVector
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.FakeEditGuard
import de.ionnetwork.voxelsniper.minestom.FakeEditTarget
import net.kyori.adventure.text.Component
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Commands read the map, so they belong on the map's thread.
 *
 * `/v`, `/vi`, `/vir` and `/vr` all build a `BlockHelper(player)` and ray-trace for the block the
 * builder is looking at (`VoxelVoxelCommand:35`, `VoxelInkCommand:36`, `VoxelInkReplaceCommand:36`,
 * `VoxelReplaceCommand:35` in VoxelSniperCore 8.14.0), and `/vox` scans a column with
 * `getHighestBlockYAt` (`VoxelVoxCommand:55,101`). Every one of those reaches `EditTarget.blockAt`,
 * which the SPI marks "Map thread only". Minestom runs a command executor on the thread that
 * processed the packet, which is not that thread.
 */
class CommandDispatchTest {

    private val guard = FakeEditGuard()
    private val player = FakeCommandPlayer(UUID.randomUUID())

    @BeforeTest
    fun register() = EditGuards.register(guard)

    @AfterTest
    fun deregister() = EditGuards.register(null)

    @Test
    fun `a command runs on the map thread, never on the caller's`() {
        val target = FakeEditTarget().apply { deferMapThread = true }
        guard.targets[player.uniqueId] = target
        val command = RecordingCommand()

        MinestomCommandHandler.execute(command, player, "b", "b", arrayOf("ball"))

        assertFalse(command.ran, "a command that reads the map must not run on the caller's thread")
        target.runDeferredMapThread()
        assertTrue(command.ran, "the command must run once the map thread picks the work up")
        assertEquals(listOf("ball"), command.args)
    }

    /**
     * Core's usage messages substitute `%alias%`, and `VoxelUndoCommand` branches on
     * `getActiveIdentifier()`, so both have to be set on whichever thread ends up running the
     * command - and read back by the command itself, not by the caller.
     */
    @Test
    fun `the identifier and alias the player typed are in place when the command runs`() {
        val target = FakeEditTarget().apply { deferMapThread = true }
        guard.targets[player.uniqueId] = target
        val command = RecordingCommand()

        MinestomCommandHandler.execute(command, player, "b", "brush", emptyArray())
        target.runDeferredMapThread()

        assertEquals("b" to "brush", command.seenIdentifiers)
    }

    /**
     * Nine of core's fourteen commands - `/b`, `/vs`, the performer, default, variables, brush-tool,
     * material and undo commands - touch nothing but `Sniper`/`SnipeData` state. Gating them on a map
     * would take brush configuration away from a builder who has none, including the reviewer in
     * read-only mode that `EditGuard.targetFor`'s javadoc names as one of its three null cases. What
     * needs a map is the *hand-off*, not the command.
     */
    @Test
    fun `a command still runs when there is no editable map, on the calling thread`() {
        guard.targets[player.uniqueId] = null
        val command = RecordingCommand()

        MinestomCommandHandler.execute(command, player, "b", "b", arrayOf("ball"))

        assertTrue(command.ran, "a command that needs no map must still run without one")
        assertEquals(listOf("ball"), command.args)
    }

    /**
     * The handler says nothing here: for the nine commands that need no map a refusal would be a lie,
     * and the five that do read are refused by `MinestomWorld` at the read, which is where the builder
     * is told why (`MinestomWorldTargetResolutionTest`).
     */
    @Test
    fun `no map is not itself reported as a refusal`() {
        guard.targets[player.uniqueId] = null

        MinestomCommandHandler.execute(RecordingCommand(), player, "b", "b", emptyArray())

        assertEquals(emptyList(), guard.messages)
    }

    @Test
    fun `a command still runs on a server that is not a build node`() {
        EditGuards.register(null)
        val command = RecordingCommand()

        MinestomCommandHandler.execute(command, player, "b", "b", emptyArray())

        assertTrue(command.ran)
    }
}

/** Records when and with what it was run. No permission set, so `execute` reaches `doCommand`. */
private class RecordingCommand : VoxelCommand("recording") {
    var ran = false
    var args: List<String> = emptyList()
    var seenIdentifiers: Pair<String, String>? = null

    override fun doCommand(player: IPlayer, args: Array<String>): Boolean {
        ran = true
        this.args = args.toList()
        seenIdentifiers = activeIdentifier to activeAlias
        return true
    }

    override fun doSuggestion(player: IPlayer, args: Array<String>): List<String> = emptyList()
}

/**
 * Identity only, like the other player stubs here - each models the members its own path reads and
 * throws for the rest, so a test that leans on one for something it was never meant to model fails
 * loudly instead of quietly passing.
 */
private class FakeCommandPlayer(private val uuid: UUID) : IPlayer {
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
        throw NotImplementedError("FakeCommandPlayer models a uuid and a name and nothing else")
}
