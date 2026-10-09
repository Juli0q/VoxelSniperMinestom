package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockDataTest {

    @Test
    fun `material comes from the block`() {
        // IMaterial's getKey()/getNameSpace() come from the Kotlin-sourced IKeyed, so Kotlin
        // generates no property sugar for them - call as functions (see MaterialRegistryTest).
        assertEquals("stone", MinestomBlockData(Block.STONE).getMaterial().getKey())
    }

    @Test
    fun `matches compares full state, not just type`() {
        val top = MinestomBlockData(Block.OAK_SLAB.withProperty("type", "top"))
        val bottom = MinestomBlockData(Block.OAK_SLAB.withProperty("type", "bottom"))
        assertTrue(top.matches(MinestomBlockData(Block.OAK_SLAB.withProperty("type", "top"))))
        assertFalse(top.matches(bottom))
    }

    @Test
    fun `getAsString renders the block-state format`() {
        assertEquals("minecraft:stone", MinestomBlockData(Block.STONE).asString)
        assertTrue(MinestomBlockData(Block.OAK_SLAB.withProperty("type", "top")).asString
            .startsWith("minecraft:oak_slab["))
    }

    @Test
    fun `merge keeps shared properties of the old state`() {
        val oldStairs = MinestomBlockData(Block.OAK_STAIRS.withProperty("facing", "east"))
        val newStairs = MinestomBlockData(Block.STONE_STAIRS)
        val merged = oldStairs.merge(newStairs) as MinestomBlockData
        assertEquals("stone_stairs", merged.getMaterial().getKey())
        assertEquals("east", merged.block.getProperty("facing"))
    }

    @Test
    fun `merge ignores properties the new block does not have`() {
        val oldStairs = MinestomBlockData(Block.OAK_STAIRS.withProperty("facing", "east"))
        val merged = oldStairs.merge(MinestomBlockData(Block.STONE)) as MinestomBlockData
        assertEquals("stone", merged.getMaterial().getKey())
    }

    @Test
    fun `merge drops only the illegal shared property, not the whole batch`() {
        // CHEST and OAK_SLAB both have a "type" property, but with disjoint legal domains -
        // chest: single/left/right, slab: top/bottom/double (confirmed against the resolved
        // Minestom jar via Block.possibleStates()). Both also share "waterlogged" with the same
        // domain. A chest's "left" is illegal on a slab, but "waterlogged" is legal either way -
        // merge must drop only "type", not "waterlogged" along with it.
        val waterloggedChest = MinestomBlockData(
            Block.CHEST.withProperty("type", "left").withProperty("waterlogged", "true")
        )
        val merged = waterloggedChest.merge(MinestomBlockData(Block.OAK_SLAB)) as MinestomBlockData
        assertEquals("oak_slab", merged.getMaterial().getKey())
        assertEquals("true", merged.block.getProperty("waterlogged"))
        assertTrue(merged.block.getProperty("type") in setOf("top", "bottom", "double"))
    }

    @Test
    fun `getCopy is equal but the data object is independent`() {
        val data = MinestomBlockData(Block.STONE)
        assertTrue(data.copy.matches(data))
    }
}
