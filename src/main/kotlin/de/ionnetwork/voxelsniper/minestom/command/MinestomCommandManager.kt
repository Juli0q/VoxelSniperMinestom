package de.ionnetwork.voxelsniper.minestom.command

import com.github.kevindagame.command.VoxelCommand
import com.github.kevindagame.command.VoxelCommandManager
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import org.slf4j.LoggerFactory

/**
 * Registers VoxelSniper's commands with Minestom.
 *
 * Core's [VoxelCommandManager] constructor registers twelve commands and cannot be told to skip
 * any, so [registerCommand] is where `/u` (and its alias `/uu`) is dropped: the build server has
 * one undo history, fed by every write path, and VoxelSniper's own undo would know only about
 * VoxelSniper's own writes.
 *
 * Construction hazard: that superclass constructor calls the abstract [registerCommand] - which
 * this class overrides - twelve times *during `super()`*, before any of this class's own instance
 * property initializers have run. Every instance property with an inline initializer is a trap for
 * that reason, not just the one holding registrations: [registered] is a `lateinit var` with no
 * initializer expression, created lazily on first use inside [registerCommand] itself, so nothing
 * runs after `super()` to overwrite what was added during construction. The logger is a companion
 * object `val` rather than an instance property for the same reason from the other side - a
 * companion is initialized once, when the class is first *loaded*, unconditionally before that
 * (always before any instance of it can exist), so it is never JVM-default `null` when
 * [registerCommand]'s skip branch reads it for the ninth call (`VoxelUndoCommand`). An earlier
 * version of this class got [registered] right and missed this: `private val log =
 * LoggerFactory.getLogger(...)` NPE'd out of `super()` on every construction, because the skip
 * branch it is read from always fires once, for `/u`.
 * Verified in `CommandManagerConstructionHazardTest` against the real, unmodified
 * `VoxelCommandManager` from VoxelSniperCore - a probe subclass using the fixed pattern for both
 * fields keeps all eleven non-undo commands, while probes reproducing either mistake NPE instead.
 */
class MinestomCommandManager : VoxelCommandManager() {

    private lateinit var registered: MutableList<Command>

    override fun registerCommand(command: VoxelCommand) {
        if (command.identifier in SKIPPED) {
            LOG.info("VoxelSniper: not registering /{} - the build server owns undo", command.identifier)
            return
        }

        if (!::registered.isInitialized) registered = mutableListOf()

        argumentsMap[command.identifier] = command.registerTabCompletion()
        command.otherIdentifiers.forEach { argumentsMap[it] = command.registerTabCompletion() }

        val minestomCommand = Command(command.identifier, *command.otherIdentifiers.toTypedArray())

        val rest = ArgumentType.StringArray("args")
        rest.setSuggestionCallback { sender, context, suggestion ->
            val player = sender as? Player ?: return@setSuggestionCallback
            val voxelPlayer = MinestomPlayers.byUuid(player.uuid) ?: return@setSuggestionCallback
            val args = context.get(rest) ?: emptyArray()

            // EditGuards.get() and EditGuard.targetFor(...) are resolved fresh on every call, not
            // cached at init: a guard captured at init would be the right object, but a target
            // captured at init would be a target for a session that did not exist yet. On a server
            // that is not a build node (or a player with no editable session), there is no map to
            // ask, so nothing is withheld - offering everything is the safe default here, unlike
            // SnipeGuard's write path, which must refuse when there is nothing to check against.
            val target = EditGuards.get()?.targetFor(player.uuid)
            val canPlace: (String) -> Boolean = { id ->
                target?.let { t -> Block.fromKey(id)?.let(t::canEverPlace) ?: true } ?: true
            }

            // identifier is the command's own canonical name; alias is whatever name the player
            // actually typed - the same identifier/alias split Spigot's bridge makes with
            // getLabel() vs its onCommand `label` parameter. No currently-registered command has
            // an alias (VoxelUndoCommand's "uu" left with it, skipped above), but core's usage
            // messages substitute `%alias%`, so getting this right costs nothing and is correct if
            // that ever changes.
            MinestomCommandHandler
                .suggestions(command, voxelPlayer, command.identifier, context.commandName, args, canPlace)
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

        minestomCommand.addSyntax({ sender, context ->
            val player = sender as? Player ?: return@addSyntax
            val voxelPlayer = MinestomPlayers.byUuid(player.uuid) ?: return@addSyntax
            val args = context.get(rest) ?: emptyArray()
            MinestomCommandHandler.execute(command, voxelPlayer, command.identifier, context.commandName, args)
        }, rest)

        // The bare command with no arguments - `/b` on its own prints usage/settings.
        minestomCommand.setDefaultExecutor { sender, context ->
            val player = sender as? Player ?: return@setDefaultExecutor
            val voxelPlayer = MinestomPlayers.byUuid(player.uuid) ?: return@setDefaultExecutor
            MinestomCommandHandler.execute(command, voxelPlayer, command.identifier, context.commandName, emptyArray())
        }

        MinecraftServer.getCommandManager().register(minestomCommand)
        registered += minestomCommand
    }

    fun unregisterAll() {
        if (!::registered.isInitialized) return
        registered.forEach { MinecraftServer.getCommandManager().unregister(it) }
        registered.clear()
    }

    companion object {
        // A companion-object val, not an instance property: registerCommand's skip branch reads
        // this from inside super()'s twelve calls, before any instance property initializer of
        // this class has run. A companion is initialized once, when the class is first loaded -
        // always before any instance of it can exist - so unlike an instance-level `private val
        // log = ...`, nothing here can still be JVM-default `null` when registerCommand reads it.
        // registered (below, an instance lateinit var) hits the same rule from the other side: it
        // has per-instance state a logger does not, so it cannot live here instead.
        private val LOG = LoggerFactory.getLogger(MinestomCommandManager::class.java)

        /** `/u` and its alias `/uu` - see the class doc. Both belong to the one skipped `VoxelUndoCommand`. */
        private val SKIPPED = setOf("u")

        fun initialize(): MinestomCommandManager {
            val manager = MinestomCommandManager()
            instance = manager
            manager.registerBrushSubcommands()
            return manager
        }
    }
}
