package de.ionnetwork.voxelsniper.minestom.block

import com.github.kevindagame.voxelsniper.blockdata.IBlockData
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import de.ionnetwork.voxelsniper.minestom.platform.MaterialRegistry
import net.minestom.server.instance.block.Block

/**
 * One Minestom [Block] - that is, a block *state*, properties included - as VoxelSniper's block data.
 *
 * Minestom's Block instances are immutable and interned per state, so this wrapper holds no copy and
 * `getCopy` can hand back the same block.
 */
class MinestomBlockData(val block: Block) : IBlockData {

    override fun getMaterial(): VoxelMaterial = MaterialRegistry.fromBlock(block)

    /** Compares the whole state. Two slabs differing only in `type` are not a match. */
    override fun matches(blockData: IBlockData?): Boolean {
        val other = blockData as? MinestomBlockData ?: return false
        return block.compare(other.block, Block.Comparator.STATE)
    }

    override fun getAsString(): String {
        val id = block.key().asString()
        val properties = block.properties()
        if (properties.isEmpty()) return id
        return properties.entries.joinToString(",", "$id[", "]") { "${it.key}=${it.value}" }
    }

    /**
     * Takes [newData]'s block, then re-applies whichever of this state's properties the new block
     * also has. This is what lets a performer replace oak stairs with stone stairs and keep the
     * facing - and what makes replacing stairs with stone simply yield stone.
     *
     * Applied one property at a time, not as a single batch: some property *names* are reused
     * across block families with disjoint legal values (e.g. `type` on a chest is
     * single/left/right, but on a slab is top/bottom/double), so a shared name is no guarantee the
     * old value is legal on the new block. Folding key-by-key means only that one illegal property
     * is dropped, instead of an illegal value in the batch silently discarding every other
     * carried-but-legal property (e.g. `waterlogged`) along with it.
     */
    override fun merge(newData: IBlockData): IBlockData {
        val target = (newData as? MinestomBlockData)?.block ?: return newData
        val carried = block.properties().filterKeys { target.getProperty(it) != null }
        val merged = carried.entries.fold(target) { acc, (key, value) ->
            runCatching { acc.withProperty(key, value) }.getOrDefault(acc)
        }
        return MinestomBlockData(merged)
    }

    override fun getCopy(): IBlockData = MinestomBlockData(block)

    override fun equals(other: Any?): Boolean = other is MinestomBlockData && matches(other)
    override fun hashCode(): Int = block.stateId()
    override fun toString(): String = asString
}
