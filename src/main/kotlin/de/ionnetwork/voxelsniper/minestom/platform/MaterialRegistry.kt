package de.ionnetwork.voxelsniper.minestom.platform

import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * Minestom's block and item registries, as VoxelSniper materials.
 *
 * Cached because [VoxelMaterial.getMaterial] resolves through [com.github.kevindagame.voxelsniper.IVoxelsniper]
 * on *every* call, and brushes call `VoxelMaterial.AIR()` inside their inner loops. Without a cache
 * that is a registry lookup and an allocation per block placed.
 */
object MaterialRegistry {

    private val byName = ConcurrentHashMap<String, VoxelMaterial>()

    /** Sentinel: ConcurrentHashMap cannot store null, but "this server has no such block" must cache too. */
    private val ABSENT = MinestomItemMaterial(Material.AIR, "voxelsniper", "absent")

    fun of(namespace: String, key: String): VoxelMaterial? {
        val id = "$namespace:$key"
        val cached = byName.computeIfAbsent(id) { compute(it) ?: ABSENT }
        return if (cached === ABSENT) null else cached
    }

    fun fromBlock(block: Block): VoxelMaterial {
        val id = block.key().asString()
        return of(id.substringBefore(':'), id.substringAfter(':')) ?: of("minecraft", "air")!!
    }

    /**
     * Every block, then every item that has no block of the same name. VoxelSniper uses this only
     * for tab completion, so ordering is by registry order and stability matters more than speed.
     */
    fun all(): List<VoxelMaterial> {
        val result = ArrayList<VoxelMaterial>()
        val seen = HashSet<String>()
        for (block in Block.values()) {
            val id = block.key().asString()
            if (seen.add(id)) of(id.substringBefore(':'), id.substringAfter(':'))?.let { result += it }
        }
        for (material in Material.values()) {
            val id = material.key().asString()
            if (seen.add(id)) of(id.substringBefore(':'), id.substringAfter(':'))?.let { result += it }
        }
        return result
    }

    private fun compute(id: String): VoxelMaterial? {
        val namespace = id.substringBefore(':')
        val key = id.substringAfter(':')
        Block.fromKey(id)?.let { return MinestomBlockMaterial(it, namespace, key) }
        Material.fromKey(id)?.let { return MinestomItemMaterial(it, namespace, key) }
        return null
    }
}
