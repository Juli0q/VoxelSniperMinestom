package de.ionnetwork.voxelsniper.minestom.entity

import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.entity.Entity

/**
 * A Minestom entity, as VoxelSniper sees one.
 *
 * [world] is the build map the entity is being read *for*, not the Instance it stands in - the same
 * distinction [MinestomWorld] draws. Only EntityBrush and EntityRemovalBrush reach any of this.
 */
open class MinestomEntity(open val handle: Entity, val world: MinestomWorld) : IEntity {

    override fun getType(): VoxelEntityType {
        val key = handle.entityType.key()
        return VoxelEntityType(key.namespace(), key.value())
    }

    override fun getEntityId(): Int = handle.entityId

    override fun getWorld(): IWorld = world

    override fun getLocation(): BaseLocation = handle.position.let {
        BaseLocation(world, it.x(), it.y(), it.z(), it.yaw(), it.pitch())
    }

    override fun getEyeLocation(): BaseLocation = handle.position.let {
        BaseLocation(world, it.x(), it.y() + handle.eyeHeight, it.z(), it.yaw(), it.pitch())
    }

    /** Unguarded by decision - see spec section 6. Only EntityRemovalBrush reaches this. */
    override fun remove() = handle.remove()

    override fun addPassenger(entity: IEntity) {
        if (entity is MinestomEntity) handle.addPassenger(entity.handle)
    }

    /**
     * A passenger that is a player comes back as a plain [MinestomEntity], not a [MinestomPlayer].
     * That is safe here because JockeyBrush, the only caller, only inspects and ejects passengers.
     * The two paths that *do* test `entity instanceof IPlayer` are `IChunk.getEntities` and
     * [MinestomWorld.getNearbyEntities]; both still return nothing, and whichever task fills them in
     * must resolve a player through [MinestomPlayers] so the entity it hands core is the same
     * [MinestomPlayer] - and therefore the same Sniper - as that connection already owns.
     */
    override fun getPassengers(): List<IEntity> = handle.passengers.map { MinestomEntity(it, world) }

    override fun eject() {
        // Copied first: removePassenger mutates the same list getPassengers hands back.
        handle.passengers.toList().forEach { handle.removePassenger(it) }
    }

    /**
     * False for an entity we did not create, which is how [IEntity] says "that did not happen".
     * The teleport itself is asynchronous in Minestom; the returned future is not awaited, because
     * the only caller (JockeyBrush) does nothing with the outcome.
     */
    override fun teleport(other: IEntity): Boolean {
        val target = other as? MinestomEntity ?: return false
        handle.teleport(target.handle.position)
        return true
    }

    override fun getNearbyEntities(x: Int, y: Int, z: Int): List<IEntity> =
        world.getNearbyEntities(location, x.toDouble(), y.toDouble(), z.toDouble())
}
