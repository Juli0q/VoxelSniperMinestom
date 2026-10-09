package de.ionnetwork.voxelsniper.minestom.block

import com.github.kevindagame.voxelsniper.block.IBlock
import com.github.kevindagame.voxelsniper.blockdata.IBlockData
import com.github.kevindagame.voxelsniper.blockstate.IBlockState
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.voxelsniper.minestom.platform.MaterialRegistry
import net.minestom.server.instance.block.Block

/**
 * A block as it was at one moment, and the ability to put it back.
 *
 * [captured] is taken at construction and never re-read - that snapshot is the entire contract, and
 * it is what VoxelSniper's own Undo is made of. This adapter does not use that undo (the build
 * server owns history, and `undo-cache-size` is 0), so [update] is reached only by
 * `BlockStateOperation`, which `SignOverwriteBrush` emits.
 */
open class MinestomBlockState(
    private val block: MinestomBlock,
    private val captured: Block
) : IBlockState {

    override fun getBlock(): IBlock = block
    override fun getWorld(): IWorld = block.world
    override fun getX(): Int = block.bx
    override fun getY(): Int = block.by
    override fun getZ(): Int = block.bz
    override fun getLocation(): BaseLocation = block.location
    override fun getMaterial(): VoxelMaterial = MaterialRegistry.fromBlock(captured)
    override fun getBlockData(): IBlockData = MinestomBlockData(captured)

    override fun update(): Boolean = update(false)
    override fun update(force: Boolean): Boolean = update(force, true)

    /**
     * Unguarded by decision (spec section 6): a write here would skip the block profile, the tile
     * box and reviewer read-only, same as `IWorld.setBiome`. It also has nowhere to go yet -
     * `EditTarget` exposes no `Instance` handle for a state capture at an arbitrary position to
     * write through, and manufacturing one via `MinecraftServer` is explicitly out of scope here.
     * Task 11 confirmed there is no Instance to give it and never will be through this SPI, so this
     * stays a no-op: SignOverwriteBrush is unavailable until `EditChange` grows a block-entity
     * variant, and Task 12 documents it as such.
     */
    override fun update(force: Boolean, applyPhysics: Boolean): Boolean = false

    companion object {
        /**
         * The state a captured [Block] should be represented as. Signs need per-line text access
         * ([MinestomSign]); everything else is a plain snapshot.
         *
         * Detected via [Block.blockEntityType], not a key-name suffix: its own key
         * (`minecraft:sign`, `minecraft:hanging_sign`) covers standing, wall, and hanging signs of
         * every wood type without guessing from the block's own key. Deliberately not
         * `Block.registry()` - despite the brief's example, that whole accessor is
         * `@Deprecated(forRemoval = true)` in the resolved jar; `blockEntityType()` is the
         * un-deprecated way to reach the same data (confirmed via `javap -v` on `Block.class`,
         * consistent with `isAir()`/`isSolid()`/`isLiquid()`/`isFluid()` also being deprecated
         * there in favour of `air()`/`solid()`/`liquid()`/`fluid()`).
         */
        fun capture(block: MinestomBlock, captured: Block): MinestomBlockState =
            if (isSign(captured)) MinestomSign(block, captured) else MinestomBlockState(block, captured)

        private fun isSign(block: Block): Boolean {
            val entityId = block.blockEntityType()?.key()?.asString()?.substringAfter(':') ?: return false
            return entityId == "sign" || entityId == "hanging_sign"
        }
    }
}
