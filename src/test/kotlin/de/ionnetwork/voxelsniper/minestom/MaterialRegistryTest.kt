package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.platform.MaterialRegistry
import de.ionnetwork.voxelsniper.minestom.platform.MinestomBlockMaterial
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialRegistryTest {

    @Test
    fun `resolves a known block`() {
        val stone = MaterialRegistry.of("minecraft", "stone")
        assertNotNull(stone)
        // IKeyed is a Kotlin interface (com.github.kevindagame.IKeyed), so its own getNameSpace()/
        // getKey() do not get Kotlin's Java-getter property sugar - call them as functions.
        assertEquals("minecraft", stone.getNameSpace())
        assertEquals("stone", stone.getKey())
        assertTrue(stone.isBlock)
    }

    @Test
    fun `returns null for a block this server does not have`() {
        assertNull(MaterialRegistry.of("minecraft", "definitely_not_a_block"))
    }

    @Test
    fun `repeated lookups return the same instance`() {
        assertSame(MaterialRegistry.of("minecraft", "stone"), MaterialRegistry.of("minecraft", "stone"))
    }

    @Test
    fun `air reports isAir`() {
        assertTrue(MaterialRegistry.of("minecraft", "air")!!.isAir)
        assertTrue(!MaterialRegistry.of("minecraft", "stone")!!.isAir)
    }

    @Test
    fun `fromBlock round-trips a block with properties`() {
        val slab = Block.OAK_SLAB.withProperty("type", "top")
        val material = MaterialRegistry.fromBlock(slab)
        assertEquals("oak_slab", material.getKey())
        // The material is the *type*, not the state - properties belong to IBlockData.
        assertEquals(Block.OAK_SLAB.key().asString(), (material as MinestomBlockMaterial).block.key().asString())
    }
}
