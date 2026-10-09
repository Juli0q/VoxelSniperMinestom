package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.entity.grantsVoxelSniperNode
import de.ionnetwork.voxelsniper.minestom.entity.sendWithBuildServerPrefix
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two rules that decide what a builder on this server can do and how they hear about it.
 *
 * Both live as top-level functions because a `MinestomPlayer` cannot be built in a unit test - its
 * `Sniper` reaches the `VoxelSniper` singleton and it needs a live Minestom `Player`.
 */
class BuildServerAccessTest {

    @Test
    fun `every VoxelSniper node is granted, so no builder is locked out of a brush`() {
        assertTrue(grantsVoxelSniperNode("voxelsniper.sniper"), "the node every command checks")
        assertTrue(grantsVoxelSniperNode("voxelsniper.brush.erode"))
        assertTrue(grantsVoxelSniperNode("voxelsniper.brush.ball"))
        assertTrue(grantsVoxelSniperNode("voxelsniper.undouser"))
    }

    @Test
    fun `nodes that are not VoxelSniper's are not answered for`() {
        assertFalse(grantsVoxelSniperNode("buildserver.admin"))
        assertFalse(grantsVoxelSniperNode("minecraft.command.op"))
        assertFalse(grantsVoxelSniperNode(""))
        assertFalse(
            grantsVoxelSniperNode("voxelsniper"),
            "the bare plugin name is not one of core's nodes; every real one carries a dot"
        )
    }

    @Test
    fun `a message goes out with the build server's prefix when the player has a map`() {
        val target = FakeEditTarget()
        val bare = mutableListOf<Component>()

        sendWithBuildServerPrefix(target, Component.text("20 blocks changed")) { bare += it }

        assertEquals(listOf<Component>(Component.text("20 blocks changed")), target.components.toList())
        assertTrue(bare.isEmpty(), "a prefixed message must not also be sent bare")
    }

    @Test
    fun `a message still reaches a player with no map, unprefixed`() {
        val bare = mutableListOf<Component>()

        sendWithBuildServerPrefix(null, Component.text("no build session")) { bare += it }

        assertEquals(listOf<Component>(Component.text("no build session")), bare.toList())
    }
}
