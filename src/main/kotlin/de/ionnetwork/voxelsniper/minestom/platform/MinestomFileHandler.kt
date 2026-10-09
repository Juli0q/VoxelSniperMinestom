package de.ionnetwork.voxelsniper.minestom.platform

import com.github.kevindagame.voxelsniper.fileHandler.IFileHandler
import java.io.File

/**
 * VoxelSniper's data directory on a build node.
 *
 * Core genuinely needs files on disk: `VoxelSniperConfiguration` and `Messages.load` both build a
 * `File` against [getDataFolder] and call [saveResource] when it is missing, so an in-memory or
 * in-jar-only handler leaves every message unresolved. The defaults ship inside this jar and are
 * extracted once, which keeps the node's copy editable without making the jar the source of truth.
 */
class MinestomFileHandler(private val root: File) : IFileHandler {

    init {
        root.mkdirs()
    }

    override fun getDataFolder(): File = root

    override fun saveResource(loader: ClassLoader, resourcePath: String, replace: Boolean) {
        val destination = File(root, resourcePath)
        if (destination.exists() && !replace) return
        destination.parentFile?.mkdirs()
        val stream = loader.getResourceAsStream(resourcePath)
            ?: error("VoxelSniper resource '$resourcePath' is missing from the extension jar")
        stream.use { input -> destination.outputStream().use { input.copyTo(it) } }
    }
}
