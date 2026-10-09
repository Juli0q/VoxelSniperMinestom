package de.ionnetwork.voxelsniper.minestom.block

import com.github.kevindagame.voxelsniper.block.BlockFace
import com.github.kevindagame.voxelsniper.block.IBlock
import com.github.kevindagame.voxelsniper.blockdata.IBlockData
import com.github.kevindagame.voxelsniper.blockstate.IBlockState
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import de.ionnetwork.voxelsniper.minestom.platform.MaterialRegistry
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block

/**
 * One position in a build map.
 *
 * Holds coordinates, not a block: VoxelSniper keeps IBlock references across a whole brush pass and
 * expects a read to reflect the map now, so every accessor goes back to the [de.ionnetwork.minestomconversion.extension.edit.EditTarget].
 */
class MinestomBlock(
    val world: MinestomWorld,
    val bx: Int,
    val by: Int,
    val bz: Int
) : IBlock {

    private fun raw(): Block = world.target.blockAt(bx, by, bz)

    override fun getLocation(): BaseLocation =
        BaseLocation(world, bx.toDouble(), by.toDouble(), bz.toDouble())

    override fun getMaterial(): VoxelMaterial = MaterialRegistry.fromBlock(raw())
    override fun getBlockData(): IBlockData = MinestomBlockData(raw())
    override fun getState(): IBlockState = MinestomBlockState.capture(this, raw())

    override fun getX(): Int = bx
    override fun getY(): Int = by
    override fun getZ(): Int = bz

    override fun isEmpty(): Boolean = raw().air()
    override fun isLiquid(): Boolean = raw().liquid()

    override fun getRelative(x: Int, y: Int, z: Int): IBlock =
        MinestomBlock(world, bx + x, by + y, bz + z)

    override fun getRelative(face: BlockFace): IBlock =
        getRelative(face.modX, face.modY, face.modZ)

    override fun getFace(block: IBlock): BlockFace? {
        val dx = block.x - bx
        val dy = block.y - by
        val dz = block.z - bz
        return BlockFace.values().firstOrNull { it.modX == dx && it.modY == dy && it.modZ == dz }
    }

    /**
     * Every block write in a guarded snipe goes through
     * [de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard], which stages the whole change set and
     * cancels the operations so core's own write loop never runs. Reaching this method means a write
     * escaped the guard - which would bypass the block profile, the tile box and reviewer read-only
     * silently. Failing loudly is the only safe behaviour.
     */
    override fun setBlockData(blockData: IBlockData) = setBlockData(blockData, true)

    override fun setBlockData(blockData: IBlockData, applyPhysics: Boolean): Unit =
        error("VoxelSniper tried to write ($bx,$by,$bz) directly; guarded writes must go through EditTarget.stage")

    override fun setMaterial(material: VoxelMaterial) = setMaterial(material, true)
    override fun setMaterial(material: VoxelMaterial, applyPhysics: Boolean): Unit =
        setBlockData(material.createBlockData(), applyPhysics)

    // Minestom models no redstone power graph. Brushes that ask (VoltMeterBrush) report zero, which
    // is honest: there is no power on a build map.
    override fun isBlockFacePowered(face: BlockFace): Boolean = false
    override fun isBlockFaceIndirectlyPowered(face: BlockFace): Boolean = false
    override fun isBlockIndirectlyPowered(): Boolean = false
    override fun isBlockPowered(): Boolean = false

    override fun equals(other: Any?): Boolean =
        other is MinestomBlock && other.bx == bx && other.by == by && other.bz == bz && other.world === world

    override fun hashCode(): Int = (bx * 31 + by) * 31 + bz
}
