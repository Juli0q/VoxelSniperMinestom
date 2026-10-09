package de.ionnetwork.voxelsniper.minestom.command

import com.github.kevindagame.command.VoxelCommand
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import de.ionnetwork.minestomconversion.extension.edit.EditGuards

/**
 * Turns a [VoxelCommand] into something Minestom can execute and complete.
 *
 * The two pure helpers ([filterPlaceable] and [partialMatches]) are separate from the Minestom
 * wiring on purpose: suggestion filtering is the part with behaviour worth testing, and it needs no
 * server to test - see `CommandSuggestionTest`.
 */
object MinestomCommandHandler {

    /**
     * Runs [command] on the thread that owns the builder's map, when there is one.
     *
     * Commands read the map: `/v`, `/vi`, `/vir` and `/vr` build a `BlockHelper(player)` and
     * ray-trace for the block the builder is looking at (`VoxelVoxelCommand:35`, `VoxelInkCommand:36`,
     * `VoxelInkReplaceCommand:36`, `VoxelReplaceCommand:35`), and `/vox` scans a column through
     * `getHighestBlockYAt` (`VoxelVoxCommand:55,101`). All of those reach `EditTarget.blockAt`, which
     * the SPI marks "Map thread only - see runOnMapThread", for the same reason a snipe is dispatched
     * there: Minestom chunk access is not thread-safe, and Minestom runs a command executor on
     * whichever thread processed the packet.
     *
     * **What is gated is the hand-off, not the command.** The other nine commands - `/b`, `/vs`, the
     * performer, default, variables, brush-tool, material and undo commands - touch nothing but
     * `Sniper`/`SnipeData` state, and a builder with no map must still be able to set their brush:
     * `EditGuard.targetFor` names "a reviewer who has not switched out of read-only mode" as one of
     * the three reasons it answers null, and that reviewer still configures brushes. So with no map
     * the command runs here, on the calling thread, exactly as it did before this hand-off existed.
     * That is not a thread-safety hole: a command that *does* read never reaches `blockAt`, because
     * [MinestomWorld][de.ionnetwork.voxelsniper.minestom.world.MinestomWorld] refuses to resolve a map
     * that is not there and tells the builder why, and `VoxelCommand.execute`'s own `catch (Exception)`
     * reports it. No hardcoded list of which commands read: such a list goes stale the moment core
     * adds one, and the target-presence check covers the same ground for free.
     *
     * Only *execution* moves. [suggestions] stays on the calling thread because tab completion has to
     * return a list immediately, and it can: no `doSuggestion` in VoxelSniperCore 8.14.0 touches the
     * world (checked across all twelve that override it), so there is nothing there to race with.
     *
     * The identifier and alias are set with the execution, not before the hand-off, because they are
     * read by the command itself - core's usage messages substitute `%alias%` and `VoxelUndoCommand`
     * branches on `getActiveIdentifier()` - so they have to be in place on the thread that runs it.
     *
     * Returns nothing. `VoxelCommand.execute`'s `Boolean` is Spigot's "print the usage line" signal;
     * Minestom's `CommandExecutor` is a void callback and both call sites here already discarded it,
     * so deferring loses nothing that was being read.
     */
    fun execute(command: VoxelCommand, player: IPlayer, identifier: String, alias: String, args: Array<String>) {
        // Resolved per call and used for one thing only: deciding which thread runs the command.
        // Null on a server that is not a build node, or for a player with no editable session.
        val target = EditGuards.get()?.targetFor(player.uniqueId)
        if (target == null) {
            run(command, player, identifier, alias, args)
            return
        }
        target.runOnMapThread { run(command, player, identifier, alias, args) }
    }

    private fun run(command: VoxelCommand, player: IPlayer, identifier: String, alias: String, args: Array<String>) {
        // activeIdentifier/activeAlias are plain, non-volatile fields on VoxelCommand, and command
        // is one instance per command shared across every player - core's own design, not this
        // hand-off's. Now that execute() dispatches onto the map thread while the packet thread can
        // still be running another command on the same VoxelCommand, a race can bind a wrong
        // %alias% into a usage message. Bounded to that: a cosmetic misprint, not a write. Fixing it
        // for real needs a core API change (per-invocation state instead of per-command fields),
        // which is out of scope here.
        command.activeIdentifier = identifier
        command.activeAlias = alias
        command.execute(player, args)
    }

    /**
     * Suggestions for [command]'s current argument, filtered to what this map will accept and to
     * what the player has actually typed so far.
     */
    fun suggestions(
        command: VoxelCommand,
        player: IPlayer,
        identifier: String,
        alias: String,
        args: Array<String>,
        canPlace: (String) -> Boolean
    ): List<String> {
        command.activeIdentifier = identifier
        command.activeAlias = alias
        val offered = command.doSuggestion(player, args)
        val token = args.lastOrNull() ?: ""
        return partialMatches(token, filterPlaceable(offered, canPlace))
    }

    /**
     * Drops any suggestion that names a block this map will never accept.
     *
     * A suggestion is treated as a material only if it looks like one (`namespace:key`); brush
     * names, numbers and flags pass through untouched, because [canPlace] knows nothing about them
     * and would reject them all.
     */
    fun filterPlaceable(offered: List<String>, canPlace: (String) -> Boolean): List<String> =
        offered.filter { !it.contains(':') || canPlace(it) }

    /**
     * Matches the beginning of the token, case-insensitively - the behaviour players expect.
     *
     * A candidate also matches when the part after its `namespace:` prefix starts with the token:
     * `MaterialCommand.registerTabCompletion` offers both `minecraft:stone` and the bare `stone`,
     * but nothing guarantees the short form is present for every candidate, so a builder typing
     * "sto" must still find `minecraft:stone` on its own. `sandstone` is correctly excluded,
     * because its key does not *start* with "sto" even though it contains it.
     */
    fun partialMatches(token: String, candidates: List<String>): List<String> {
        if (token.isEmpty()) return candidates
        return candidates.filter { candidate ->
            candidate.startsWith(token, ignoreCase = true) ||
                (candidate.contains(':') && candidate.substringAfter(':').startsWith(token, ignoreCase = true))
        }
    }
}
