package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinestomWorldTest {

    @Test
    fun `reads come from the edit target`() {
        val target = FakeEditTarget()
        target.set(10, 70, -3, Block.DIAMOND_BLOCK)
        val world = MinestomWorld(target)
        assertEquals("diamond_block", world.getBlock(10, 70, -3).material.getKey())
        assertEquals("air", world.getBlock(0, 0, 0).material.getKey())
    }

    @Test
    fun `world heights come from the target, not from Minestom defaults`() {
        val world = MinestomWorld(FakeEditTarget(minY = -32, maxY = 200))
        assertEquals(-32, world.minWorldHeight)
        assertEquals(200, world.maxWorldHeight)
        assertTrue(world.isInWorldHeight(199))
        assertFalse(world.isInWorldHeight(200))
        assertFalse(world.isInWorldHeight(-33))
    }

    @Test
    fun `setBlockData throws - a guarded write must go through stage`() {
        val world = MinestomWorld(FakeEditTarget())
        val block = world.getBlock(1, 2, 3)
        assertFailsWith<IllegalStateException> { block.setBlockData(MinestomBlockData(Block.STONE)) }
    }

    @Test
    fun `getRelative walks the same world`() {
        val target = FakeEditTarget()
        target.set(5, 71, 5, Block.STONE)
        val world = MinestomWorld(target)
        val below = world.getBlock(5, 72, 5).getRelative(0, -1, 0)
        assertEquals("stone", below.material.getKey())
        assertEquals(71, below.y)
    }

    @Test
    fun `highest block scans down from maxY and reports minY when the column is empty`() {
        val target = FakeEditTarget(minY = 0, maxY = 128)
        target.set(2, 40, 2, Block.STONE)
        val world = MinestomWorld(target)
        assertEquals(40, world.getHighestBlockYAt(2, 2))
        assertEquals(0, world.getHighestBlockYAt(9, 9))
    }

    @Test
    fun `chunk coordinates floor correctly for negative positions`() {
        val world = MinestomWorld(FakeEditTarget())
        assertEquals(-1, world.getChunkAtLocation(-1, -1).x)
        assertEquals(-1, world.getChunkAtLocation(-1, -1).z)
    }

    @Test
    fun `generateTree returns null - Minestom has no tree generator`() {
        val world = MinestomWorld(FakeEditTarget())
        assertEquals(null, world.generateTree(world.getBlock(0, 0, 0).location, com.github.kevindagame.voxelsniper.treeType.VoxelTreeType("minecraft", "oak"), false))
    }
}
