package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hand-written required-methods list can go stale in *either* direction. A name the SPI no
 * longer has makes a correctly matched pair of jars report itself as mismatched, and the server
 * then refuses to load VoxelSniper for a reason that is not true. This fails the build instead.
 */
class SpiCompatibilityTest {

    @Test
    fun `every required method actually exists on EditTarget`() {
        val available = EditTarget::class.java.methods.mapTo(HashSet()) { it.name }
        val bogus = VoxelSniperMinestomExtension.REQUIRED_TARGET_METHODS.filterNot { it in available }
        assertTrue(bogus.isEmpty(), "REQUIRED_TARGET_METHODS names methods EditTarget does not have: $bogus")
    }

    @Test
    fun `the list is not empty`() {
        assertTrue(VoxelSniperMinestomExtension.REQUIRED_TARGET_METHODS.isNotEmpty())
    }
}
