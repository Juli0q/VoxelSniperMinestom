package de.ionnetwork.voxelsniper.minestom.entity

import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.vector.VoxelVector
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.voxelsniper.minestom.platform.MaterialRegistry
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import java.util.UUID

/**
 * A builder, as VoxelSniper sees them.
 *
 * Adventure Components pass straight through. VoxelSniperCore is compiled against Adventure 4.13.1
 * and the server ships 5.2.0, but the 22 Adventure members core actually references all resolve
 * against 5.2.0 and behave identically, so we bind to the server's copy and relocate nothing - see
 * the Adventure comment in `build.gradle.kts`. That is the whole reason [sendMessage] can hand its
 * argument to Minestom unchanged instead of serialising it across a shaded boundary.
 *
 * One of these exists per connection, held by [MinestomPlayers], because [sniper] is where a
 * builder's brush, brush size, performer and tool assignments live.
 */
class MinestomPlayer(
    override val handle: Player,
    world: MinestomWorld
) : MinestomEntity(handle, world), IPlayer {

    private val sniper = Sniper(this)

    override fun getUniqueId(): UUID = handle.uuid

    override fun getName(): String = handle.username

    override fun getSniper(): Sniper = sniper

    /**
     * Core only ever reaches the Component overload - every message it sends is built from
     * `Messages`, which is a `ComponentLike`. This overload exists because [IPlayer] declares it,
     * and it takes the string literally rather than parsing legacy colour codes, because a caller
     * that wanted markup would have passed a Component.
     */
    override fun sendMessage(message: String) = sendMessage(Component.text(message))

    /**
     * Every message VoxelSniper sends carries the build server's own chat prefix, so a brush
     * confirmation reads like the rest of the server rather than arriving bare next to one.
     *
     * The prefix lives on [EditTarget.sendPrefixed] rather than here because the wording is the
     * build server's to choose - this adapter has neither the language manager nor any business
     * knowing the player's language. A player with no map has no target to ask, so their message
     * goes out unprefixed; that path is reachable only before a build session exists.
     */
    override fun sendMessage(message: Component) =
        sendWithBuildServerPrefix(EditGuards.get()?.targetFor(handle.uuid), message, handle::sendMessage)

    /**
     * Every VoxelSniper permission node is granted. On a build node the authorization question has
     * already been asked and answered before a brush is ever reached.
     *
     * `EditGuard.targetFor` decides which map a player may edit, and returns null for a visitor, a
     * session on another node, or a reviewer who has not left read-only mode. `EditTarget.check`
     * then rules on the whole change set against that map's block profile, tile box and caps. A
     * builder holding a brush has passed the first gate; nothing they do with it escapes the second.
     *
     * VoxelSniper's node model is a Bukkit-era idea for servers with nothing in front of the world.
     * Layering it on top here adds a second, weaker answer to a question the build server has
     * already answered - and this adapter shipped once with exactly that fault, denying every gated
     * brush to every non-operator while the guard was perfectly willing to let them build.
     *
     * Scoped to VoxelSniper's own prefix so this never answers for a node core did not ask about.
     * Core asks only about its own: `voxelsniper.sniper` per command, `voxelsniper.brush.<name>`
     * per brush, `voxelsniper.undouser` for an undo command this adapter does not register.
     */
    override fun hasPermission(permissionNode: String): Boolean = grantsVoxelSniperNode(permissionNode)

    override fun isSneaking(): Boolean = handle.isSneaking

    /** Air for an empty hand, and for any item this server has no [VoxelMaterial] for. */
    override fun getItemInHand(): VoxelMaterial {
        val key = handle.itemInMainHand.material().key()
        return MaterialRegistry.of(key.namespace(), key.value())
            ?: MaterialRegistry.of("minecraft", "air")!!
    }

    override fun teleport(location: BaseLocation) {
        handle.teleport(Pos(location.x, location.y, location.z, location.yaw, location.pitch))
    }

    /** No brush needs this on a build map; JockeyBrush is the only caller and it is unguarded anyway. */
    override fun launchProjectile(type: VoxelEntityType, velocity: VoxelVector): IEntity =
        error("VoxelSniper cannot launch projectiles on a build map")

}

/**
 * Whether VoxelSniper grants [permissionNode]. See [MinestomPlayer.hasPermission] for why the
 * answer is always yes for its own nodes.
 *
 * Top-level and internal so the rule is testable: a [MinestomPlayer] cannot be constructed in a
 * unit test, because its `Sniper` reaches the `VoxelSniper` singleton and it needs a live Minestom
 * `Player`.
 */
internal fun grantsVoxelSniperNode(permissionNode: String): Boolean =
    permissionNode.startsWith("voxelsniper.")

/**
 * Sends [message] with the build server's prefix when the player has a map, and bare when they do
 * not. Separated from [MinestomPlayer] for the same testability reason as [grantsVoxelSniperNode].
 */
internal fun sendWithBuildServerPrefix(
    target: EditTarget?,
    message: Component,
    bare: (Component) -> Unit
) {
    if (target != null) target.sendPrefixed(message) else bare(message)
}
