package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.command.MinestomCommandHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandSuggestionTest {

    @Test
    fun `suggestions drop blocks the map will never accept`() {
        val offered = listOf("minecraft:stone", "minecraft:bedrock", "minecraft:dirt")
        val filtered = MinestomCommandHandler.filterPlaceable(offered) { it != "minecraft:bedrock" }
        assertEquals(listOf("minecraft:stone", "minecraft:dirt"), filtered)
    }

    @Test
    fun `non-material suggestions are passed through untouched`() {
        val offered = listOf("ball", "erode", "5")
        assertEquals(offered, MinestomCommandHandler.filterPlaceable(offered) { false })
    }

    @Test
    fun `partial matching is anchored at the start of the token`() {
        val matches = MinestomCommandHandler.partialMatches("sto", listOf("minecraft:stone", "minecraft:sandstone"))
        assertTrue(matches.contains("minecraft:stone"))
        assertFalse(matches.contains("minecraft:sandstone"), "sandstone does not start with sto")
    }

    @Test
    fun `an empty token matches everything`() {
        val all = listOf("ball", "erode")
        assertEquals(all, MinestomCommandHandler.partialMatches("", all))
    }
}
