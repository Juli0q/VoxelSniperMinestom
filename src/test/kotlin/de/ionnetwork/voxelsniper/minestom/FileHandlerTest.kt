package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.voxelsniper.minestom.platform.MinestomFileHandler
import de.ionnetwork.voxelsniper.minestom.platform.MinestomVoxelSniper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileHandlerTest {

    private fun tempRoot(): File = Files.createTempDirectory("vs-test").toFile()

    @Test
    fun `the data folder is created if it does not exist`() {
        val root = File(tempRoot(), "voxelsniper")
        MinestomFileHandler(root)
        assertTrue(root.isDirectory)
    }

    @Test
    fun `saveResource extracts a bundled resource`() {
        val root = File(tempRoot(), "voxelsniper")
        val handler = MinestomFileHandler(root)
        handler.saveResource(javaClass.classLoader, "config.yml", false)
        val extracted = File(root, "config.yml")
        assertTrue(extracted.isFile)
        assertTrue(extracted.readText().contains("undo-cache-size"))
    }

    @Test
    fun `saveResource without replace leaves an existing file alone`() {
        val root = File(tempRoot(), "voxelsniper")
        val handler = MinestomFileHandler(root)
        File(root, "config.yml").writeText("mine: true")
        handler.saveResource(javaClass.classLoader, "config.yml", false)
        assertEquals("mine: true", File(root, "config.yml").readText())
    }

    @Test
    fun `the shipped config disables VoxelSniper's own undo`() {
        val text = javaClass.classLoader.getResourceAsStream("config.yml")!!.bufferedReader().readText()
        assertTrue(
            Regex("""^undo-cache-size:\s*0\s*$""", RegexOption.MULTILINE).containsMatchIn(text),
            "the build server owns /undo; core must retain no history"
        )
    }

    /**
     * This is the regression test for the config.yml collision (see this class's platform package
     * for the full story): the shaded jar bundles both our config.yml and VoxelSniperCore's own copy
     * under the identical archive path "config.yml", and an empirical check against the built shadow
     * jar (`java.net.URLClassLoader` over `build/libs/VoxelSniperMinestom.jar`) showed
     * `getResourceAsStream("config.yml")` resolves to **core's** copy (undo-cache-size: 20), not ours
     * - the opposite of what "our resource is listed first in the zip" would suggest.
     *
     * Reading the resource (as the test above does) therefore cannot catch that regression, because
     * it never observes the collision: on the *test* classpath (separate roots, not one shaded jar)
     * this project's own `build/resources/main/config.yml` is resolved ahead of the dependency jar's
     * copy, which is the opposite resolution order from the packaged runtime.
     *
     * This test instead simulates "the file already on disk is core's own default" - exactly what a
     * prior extraction of the losing resource would leave behind - and asserts that
     * MinestomVoxelSniper's construction still forces the running value back to 0, independent of
     * whichever config.yml ever wins the collision.
     */
    @Test
    fun `the undo cache size is 0 even when a pre-existing config on disk says otherwise`() {
        val root = File(tempRoot(), "voxelsniper")
        root.mkdirs()
        File(root, "config.yml").writeText("undo-cache-size: 99\n")

        val platform = MinestomVoxelSniper(root)

        assertEquals(0, platform.getVoxelSniperConfiguration().getUndoCacheSize())
    }
}
