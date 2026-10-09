package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.guard.ChunkPrefetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkPrefetchTest {

    @Test
    fun `a radius inside one chunk asks for one chunk`() {
        assertEquals(1, ChunkPrefetch.keysFor(centreX = 8, centreZ = 8, radius = 4).size)
    }

    @Test
    fun `a radius spanning a chunk border asks for every chunk it touches`() {
        // x from -8 to 24 spans chunks -1, 0 and 1; same for z. Three by three.
        assertEquals(9, ChunkPrefetch.keysFor(centreX = 8, centreZ = 8, radius = 16).size)
    }

    @Test
    fun `keys are distinct per chunk and stable`() {
        assertEquals(ChunkPrefetch.key(3, -7), ChunkPrefetch.key(3, -7))
        assertTrue(ChunkPrefetch.key(3, -7) != ChunkPrefetch.key(-7, 3))
    }

    @Test
    fun `negative coordinates floor rather than truncate toward zero`() {
        // Block -1 is in chunk -1, not chunk 0.
        val keys = ChunkPrefetch.keysFor(centreX = -1, centreZ = -1, radius = 0).toSet()
        assertEquals(setOf(ChunkPrefetch.key(-1, -1)), keys)
    }

    /**
     * The packing `EditTarget.ensureLoaded` documents, decoded the way `BuildEditTarget.ensureLoaded`
     * decodes it (`(key ushr 32).toInt()` and `key.toInt()`, read from the shipped jar with
     * `javap -c`). A key that round-trips through that decoder is a key the real target will load.
     */
    @Test
    fun `keys decode to the chunk coordinates the SPI expects, negatives included`() {
        val decoded = ChunkPrefetch.keysFor(centreX = -20, centreZ = 20, radius = 0)
            .map { (it ushr 32).toInt() to it.toInt() }
        assertEquals(listOf(-2 to 1), decoded)
    }
}
