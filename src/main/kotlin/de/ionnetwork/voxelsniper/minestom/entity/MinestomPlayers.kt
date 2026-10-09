package de.ionnetwork.voxelsniper.minestom.entity

import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The connected players VoxelSniper knows about.
 *
 * Each entry owns a [com.github.kevindagame.snipe.Sniper], which is where a builder's brush, brush
 * size, performer and tool assignments live. One entry per connection, dropped on disconnect: keep
 * them and the map leaks a Sniper per player who has ever joined; rebuild them mid-session and a
 * builder silently loses their brush setup.
 */
object MinestomPlayers {

    private val players = ConcurrentHashMap<UUID, IPlayer>()

    /**
     * The entry for this connection, creating it on first sight.
     *
     * A reconnect hands us a brand-new [Player] for a uuid we may still hold, if the disconnect
     * listener has not run yet. Returning the old entry then would point every message, teleport and
     * brush read at a dead connection, so an entry is reused only while it still wraps *this*
     * [Player] - which is exactly the window over which a builder's brush setup must survive.
     */
    fun join(player: Player, world: MinestomWorld): IPlayer =
        players.compute(player.uuid) { _, existing ->
            existing.takeIf { it is MinestomPlayer && it.handle === player }
                ?: MinestomPlayer(player, world)
        }!!

    fun leave(uuid: UUID) {
        players.remove(uuid)
    }

    fun byUuid(uuid: UUID): IPlayer? = players[uuid]

    /** Exact match, as uuid lookup is - VoxelSniper never resolves a player from user input. */
    fun byName(name: String): IPlayer? = players.values.firstOrNull { it.name == name }

    fun all(): List<IPlayer> = players.values.toList()

    /** Test seam: the cache is a singleton, so tests must be able to seed and reset it. */
    internal fun put(uuid: UUID, player: IPlayer) {
        players[uuid] = player
    }

    internal fun clear() = players.clear()
}
