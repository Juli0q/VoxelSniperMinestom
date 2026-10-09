package de.ionnetwork.voxelsniper.minestom.world

import com.github.kevindagame.voxelsniper.chunk.IChunk
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.world.IWorld

/** A 16x16 column of a build map. Entities are not exposed - see MinestomWorld.getNearbyEntities. */
class MinestomChunk(private val world: MinestomWorld, private val cx: Int, private val cz: Int) : IChunk {
    override fun getX(): Int = cx
    override fun getZ(): Int = cz
    override fun getWorld(): IWorld = world
    override fun getEntities(): Iterable<IEntity> = emptyList()
}
