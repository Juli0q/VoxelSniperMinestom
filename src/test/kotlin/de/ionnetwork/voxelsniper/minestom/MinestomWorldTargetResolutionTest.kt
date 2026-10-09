package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A world must never hold the target it reads through.
 *
 * `EditGuard.targetFor`'s javadoc: "Resolved per call rather than cached: a session's map, profile
 * and role all change during a visit (a profile switch, a reviewer enabling edit mode, a BuildSwap
 * rotation), and a cached target would keep answering with the old one." A `MinestomWorld` that
 * captured one at spawn would keep reading and writing the *old* map for the rest of the connection,
 * which is silent cross-map corruption and invisible to every test that only ever has one map.
 */
class MinestomWorldTargetResolutionTest {

    private val owner: UUID = UUID.randomUUID()
    private val guard = FakeEditGuard()

    @BeforeTest
    fun register() = EditGuards.register(guard)

    @AfterTest
    fun deregister() = EditGuards.register(null)

    @Test
    fun `a map swapped under the world is read through, not the one seen first`() {
        val before = FakeEditTarget(minY = -64, maxY = 320).apply { set(0, 64, 0, Block.STONE) }
        val after = FakeEditTarget(minY = -32, maxY = 200).apply { set(0, 64, 0, Block.DIRT) }
        guard.targets[owner] = before
        val world = MinestomWorld(owner)

        assertEquals(-64, world.minWorldHeight)
        assertEquals("stone", world.getBlock(0, 64, 0).material.getKey())

        // A profile switch, a reviewer enabling edit mode, or a BuildSwap rotation.
        guard.targets[owner] = after

        assertEquals(-32, world.minWorldHeight, "a cached target would still answer -64")
        assertEquals("dirt", world.getBlock(0, 64, 0).material.getKey(), "a cached target would still read stone")
    }

    @Test
    fun `the world resolves its own owner's map, not whoever asked`() {
        guard.targets[owner] = FakeEditTarget()
        MinestomWorld(owner).maxWorldHeight
        assertEquals(listOf(owner), guard.asked)
    }

    @Test
    fun `a read with no editable session refuses instead of inventing an empty world`() {
        guard.targets[owner] = null
        val world = MinestomWorld(owner)
        assertFailsWith<IllegalStateException> { world.getBlock(0, 64, 0).material }
    }

    /**
     * The refusal is the only place that knows a read was refused, so it is the only place that can
     * say so. Without this a builder with no map running `/v` sees core's generic command-error
     * message and an unattributed stack trace in the console; with it they get the build server's own
     * reason, in their own language, the same one every other build-server command gives them.
     */
    @Test
    fun `a refused read tells the builder why before it throws`() {
        guard.targets[owner] = null
        val world = MinestomWorld(owner)

        assertFailsWith<IllegalStateException> { world.getBlock(0, 64, 0).material }

        assertEquals(listOf(owner to guard.reason), guard.messages)
    }

    @Test
    fun `a read on a server that is not a build node refuses`() {
        EditGuards.register(null)
        assertFailsWith<IllegalStateException> { MinestomWorld(owner).minWorldHeight }
    }
}
