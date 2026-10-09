package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.block.MinestomSign
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockStateTest {

    @Test
    fun `a state captures the block as it was, not as it becomes`() {
        val target = FakeEditTarget()
        target.set(4, 64, 4, Block.STONE)
        val world = MinestomWorld(target)
        val state = world.getBlock(4, 64, 4).state

        target.set(4, 64, 4, Block.DIRT)

        // IMaterial's getKey() comes from the Kotlin-sourced IKeyed, so Kotlin generates no
        // property sugar for it - call as a function (see BlockDataTest, MaterialRegistryTest).
        assertEquals("stone", state.getMaterial().getKey(), "the captured state must not follow later edits")
        assertEquals("dirt", world.getBlock(4, 64, 4).getMaterial().getKey())
    }

    @Test
    fun `a state reports its own position`() {
        val world = MinestomWorld(FakeEditTarget())
        val state = world.getBlock(-7, 12, 88).state
        assertEquals(-7, state.x)
        assertEquals(12, state.y)
        assertEquals(88, state.z)
    }

    @Test
    fun `getState dispatches to MinestomSign for every sign variant, and nothing else`() {
        val target = FakeEditTarget()
        target.set(0, 0, 0, Block.OAK_SIGN)
        target.set(1, 0, 0, Block.OAK_WALL_SIGN)
        target.set(2, 0, 0, Block.OAK_HANGING_SIGN)
        target.set(3, 0, 0, Block.STONE)
        target.set(4, 0, 0, Block.CHEST)
        val world = MinestomWorld(target)

        assertTrue(world.getBlock(0, 0, 0).state is MinestomSign, "standing sign")
        assertTrue(world.getBlock(1, 0, 0).state is MinestomSign, "wall sign")
        assertTrue(world.getBlock(2, 0, 0).state is MinestomSign, "hanging sign")
        assertFalse(world.getBlock(3, 0, 0).state is MinestomSign, "plain block")
        assertFalse(world.getBlock(4, 0, 0).state is MinestomSign, "a different block entity (chest)")
    }
}
