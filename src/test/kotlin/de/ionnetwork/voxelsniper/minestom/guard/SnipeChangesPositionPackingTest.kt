package de.ionnetwork.voxelsniper.minestom.guard

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises `pack`/`unpackX`/`unpackY`/`unpackZ` directly at the signed limits of each bit field
 * (26 bits for x/z: -33554432..33554431; 12 bits for y: -2048..2047) and with mixed signs.
 *
 * Sign extension is easy to get subtly wrong in one field while the others still happen to work
 * (e.g. `unpackY`'s plain mask needs an explicit sign-extend that x/z's shift-based unpacking gets
 * for free); a single large-negative-coordinate test would not catch that. This locks in the exact
 * boundary behaviour on the real JVM, not just a model of it.
 */
class SnipeChangesPositionPackingTest {
    @Test
    fun `round trip at extremes and mixed signs`() {
        val cases = listOf(
            Triple(33554431, 2047, 33554431),
            Triple(-33554432, -2048, -33554432),
            Triple(33554431, -2048, -33554432),
            Triple(-33554432, 2047, 33554431),
            Triple(0, 0, 0),
            Triple(-1, -1, -1),
            Triple(-2_000_000, -64, 1_500_000),
            Triple(1, -1, 1),
            Triple(-1, 1, -1)
        )
        for ((x, y, z) in cases) {
            val packed = SnipeChanges.pack(x, y, z)
            assertEquals(x, SnipeChanges.unpackX(packed), "x for $x,$y,$z")
            assertEquals(y, SnipeChanges.unpackY(packed), "y for $x,$y,$z")
            assertEquals(z, SnipeChanges.unpackZ(packed), "z for $x,$y,$z")
        }
    }
}
