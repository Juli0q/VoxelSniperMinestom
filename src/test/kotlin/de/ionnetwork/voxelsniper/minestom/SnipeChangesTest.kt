package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.util.brushOperation.BlockOperation
import com.github.kevindagame.voxelsniper.location.BaseLocation
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import de.ionnetwork.voxelsniper.minestom.guard.SnipeChanges
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnipeChangesTest {

    private val world = MinestomWorld(FakeEditTarget())

    private fun op(x: Int, y: Int, z: Int, block: Block) = BlockOperation(
        BaseLocation(world, x.toDouble(), y.toDouble(), z.toDouble()),
        MinestomBlockData(Block.AIR),
        MinestomBlockData(block)
    )

    @Test
    fun `size counts positions`() {
        val changes = SnipeChanges(listOf(op(0, 0, 0, Block.STONE), op(1, 0, 0, Block.STONE)))
        assertEquals(2, changes.size())
    }

    @Test
    fun `a repeated position counts once and the last write wins`() {
        val changes = SnipeChanges(listOf(op(5, 5, 5, Block.STONE), op(5, 5, 5, Block.DIRT)))
        assertEquals(1, changes.size())
        val seen = mutableListOf<Block>()
        changes.forEach { _, _, _, block -> seen += block }
        assertEquals(listOf(Block.DIRT), seen)
    }

    @Test
    fun `bounds span every position, including negatives`() {
        val changes = SnipeChanges(listOf(op(-10, -60, 4, Block.STONE), op(3, 100, -8, Block.STONE)))
        assertEquals(-10, changes.minX()); assertEquals(3, changes.maxX())
        assertEquals(-60, changes.minY()); assertEquals(100, changes.maxY())
        assertEquals(-8, changes.minZ()); assertEquals(4, changes.maxZ())
    }

    @Test
    fun `forEach reports the original coordinates, negatives included`() {
        val changes = SnipeChanges(listOf(op(-2_000_000, -64, 1_500_000, Block.STONE)))
        var seen: Triple<Int, Int, Int>? = null
        changes.forEach { x, y, z, _ -> seen = Triple(x, y, z) }
        assertEquals(Triple(-2_000_000, -64, 1_500_000), seen)
    }

    @Test
    fun `distinctBlocks collapses repeats so the rules are asked once per state`() {
        val changes = SnipeChanges(
            listOf(op(0, 0, 0, Block.STONE), op(1, 0, 0, Block.STONE), op(2, 0, 0, Block.DIRT))
        )
        assertEquals(setOf(Block.STONE, Block.DIRT), changes.distinctBlocks())
    }

    @Test
    fun `an empty operation list is empty`() {
        assertTrue(SnipeChanges(emptyList()).isEmpty())
    }

    @Test
    fun `distinctBlocks does not report a state overwritten before it ever landed`() {
        // Same cell, STONE then AIR - e.g. an ErodeBrush sweep. Only AIR was ever actually written.
        val changes = SnipeChanges(listOf(op(0, 0, 0, Block.STONE), op(0, 0, 0, Block.AIR)))
        assertEquals(setOf(Block.AIR), changes.distinctBlocks())
    }
}
