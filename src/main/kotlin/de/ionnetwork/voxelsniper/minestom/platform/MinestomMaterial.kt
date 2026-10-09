package de.ionnetwork.voxelsniper.minestom.platform

import com.github.kevindagame.voxelsniper.blockdata.IBlockData
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material

/**
 * A block this server's registry knows, as VoxelSniper's notion of a material.
 *
 * [block] is the block's *default* state. Properties are not a material's business - they live on
 * [IBlockData] - so two states of the same block share one material instance.
 */
class MinestomBlockMaterial(
    val block: Block,
    namespace: String,
    key: String
) : VoxelMaterial(namespace, key) {

    override fun createBlockData(): IBlockData = MinestomBlockData(block)

    /**
     * Parses a block-state string like `oak_slab[type=top]`, falling back to the default state when
     * the properties do not parse. VoxelSniper hands this straight from player input (`/v` with a
     * state), so an unparseable string must not throw.
     */
    override fun createBlockData(s: String?): IBlockData {
        if (s.isNullOrEmpty()) return MinestomBlockData(block)
        val open = s.indexOf('[')
        if (open < 0 || !s.endsWith("]")) return MinestomBlockData(block)
        val properties = s.substring(open + 1, s.length - 1)
            .split(',')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
            }
            .toMap()
        return MinestomBlockData(runCatching { block.withProperties(properties) }.getOrDefault(block))
    }

    // block.isAir()/block.isSolid() are deprecated in this Minestom build in favor of the
    // unprefixed air()/solid(); those are the current, non-deprecated equivalents.
    override fun isAir(): Boolean = block.air()
    override fun isTransparent(): Boolean = !block.solid()
    override fun isBlock(): Boolean = true
}

/**
 * An item with no block form - a bucket, a sword. VoxelSniper needs these only so `/v` can name
 * what a player is holding; asking one for block data yields air.
 */
class MinestomItemMaterial(
    val material: Material,
    namespace: String,
    key: String
) : VoxelMaterial(namespace, key) {

    override fun createBlockData(): IBlockData = MinestomBlockData(Block.AIR)
    override fun createBlockData(s: String?): IBlockData = MinestomBlockData(Block.AIR)
    override fun isAir(): Boolean = false
    override fun isTransparent(): Boolean = true
    override fun isBlock(): Boolean = false
}
