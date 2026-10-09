package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.VoxelBrushManager
import com.github.kevindagame.VoxelSniper
import com.github.kevindagame.util.Messages
import com.github.kevindagame.util.schematic.DataFolderSchematicReader
import de.ionnetwork.minestomconversion.extension.Extension
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.voxelsniper.minestom.command.MinestomCommandManager
import de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers
import de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard
import de.ionnetwork.voxelsniper.minestom.listener.SnipeListener
import de.ionnetwork.voxelsniper.minestom.platform.MinestomVoxelSniper
import net.minestom.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.File

/**
 * VoxelSniper, running on Minestom, inside the build server's rules.
 *
 * Unlike its sibling WorldEditMinestom, this repository is not separate for licence reasons:
 * VoxelSniper-Reimagined is LGPL-2.1, which permits linking. It is separate to match how every
 * other extension loads, and because LGPL-2.1 s6 is cleanest satisfied across a jar boundary.
 */
class VoxelSniperMinestomExtension : Extension {

    private val log = LoggerFactory.getLogger(VoxelSniperMinestomExtension::class.java)

    private var commandManager: MinestomCommandManager? = null

    override fun id(): String = "voxelsniper"

    override fun initialize(server: MinecraftServer?) {
        if (!editSpiIsCompatible()) return

        // 1. Core reaches the platform through this static, and every step below touches it.
        val platform = MinestomVoxelSniper(File("extensions/voxelsniper"))
        VoxelSniper.voxelsniper = platform

        // 2. Brushes must exist before commands, because registerBrushSubcommands enumerates them
        //    (Task 10).
        val brushes = VoxelBrushManager.initialize()

        // 3. Messages.load reads lang.yml through the file handler, so it must follow the platform.
        //    Nothing before this point can produce a rendered message.
        DataFolderSchematicReader.initialize()
        Messages.load(platform)

        // 4. Commands, then brush subcommands.
        commandManager = MinestomCommandManager.initialize()

        // 5. The guard first, then the listeners. A snipe that fired before the guard was
        //    registered would write with no build-server rule in front of it.
        SnipeGuard.install()
        SnipeListener.install()

        log.info(
            "VoxelSniper ready on Minestom: {} brushes, {} handles",
            brushes.registeredSniperBrushes(), brushes.registeredSniperBrushHandles()
        )
    }

    override fun shutdown() {
        // Clicks first: stop new snipes arriving before taking away what checks them.
        SnipeListener.uninstall()
        SnipeGuard.uninstall()
        commandManager?.unregisterAll()
        commandManager = null
        // Symmetry with initialize(): Extension.java documents no reload path today, so nothing
        // currently re-populates this from empty, but a shutdown should not leave stale entries
        // behind for whatever loads next.
        MinestomPlayers.clear()
    }

    /**
     * Checks that the server's editing SPI has everything this build was compiled against.
     *
     * Deploy this jar against an older MinestomConversion and every command breaks - not with a
     * clear message, but with a NoSuchMethodError from deep inside command handling, once per
     * command and once per keystroke of tab completion. Failing here turns that into one line
     * naming exactly what is missing, and leaves the server otherwise working.
     */
    private fun editSpiIsCompatible(): Boolean {
        val available = EditTarget::class.java.methods.mapTo(HashSet()) { it.name }
        val missing = REQUIRED_TARGET_METHODS.filterNot { it in available }
        if (missing.isEmpty()) return true
        log.error(
            "VoxelSniper not loaded: this server's editing SPI is missing {} - the server jar is " +
                "older than this extension. Redeploy MinestomConversion and VoxelSniperMinestom together.",
            missing
        )
        return false
    }

    internal companion object {
        /**
         * The [EditTarget] methods this extension actually calls, and nothing this extension
         * merely could call or once called. Names rather than signatures on purpose: a rename or
         * removal is what actually happens when the jars drift, and a name check catches that
         * without duplicating the whole interface here.
         *
         * Listing a method this extension does not call is not conservative, it is harmful: if a
         * future MinestomConversion renames or drops that method - having no caller here to break -
         * this gate would still refuse to load, logging "the server jar is older than this
         * extension" for a mismatch that does not exist. Every name here must earn its place by a
         * real call site; `SpiCompatibilityTest` guards the list against going stale by checking
         * that every name it does list still exists on `EditTarget`, but nothing currently checks
         * the reverse - that every `EditTarget` method actually called is listed here - so keep this
         * in sync by hand when a call site is added or removed.
         */
        val REQUIRED_TARGET_METHODS = listOf(
            "runOnMapThread", "ensureLoaded", "mapId", "minY", "maxY",
            "blockAt", "check", "beginEdit", "stage", "commitEdit",
            "abortEdit", "canEverPlace", "sendPrefixed", "reportProgress", "message"
        )
    }
}
