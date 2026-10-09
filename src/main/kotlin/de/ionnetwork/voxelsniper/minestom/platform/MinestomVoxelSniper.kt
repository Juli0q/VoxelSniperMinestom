package de.ionnetwork.voxelsniper.minestom.platform

import com.github.kevindagame.voxelsniper.Environment
import com.github.kevindagame.voxelsniper.IVoxelsniper
import com.github.kevindagame.voxelsniper.biome.VoxelBiome
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.fileHandler.IFileHandler
import com.github.kevindagame.voxelsniper.fileHandler.VoxelSniperConfiguration
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.treeType.VoxelTreeType
import de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/**
 * VoxelSniperCore's view of this server.
 *
 * Assigned to the static `VoxelSniper.voxelsniper` at bootstrap; everything in core reaches the
 * platform through this one object.
 */
class MinestomVoxelSniper(dataFolder: File) : IVoxelsniper {

    private val handler: IFileHandler = MinestomFileHandler(dataFolder)
    private val logger: Logger = Logger.getLogger("VoxelSniperMinestom")

    /** Built after [handler], because its constructor reads config.yml through it. */
    private val configuration = VoxelSniperConfiguration(this)

    init {
        // THE CONFIG COLLISION, resolved here rather than trusted to the jar.
        //
        // This extension's shaded jar bundles both our own config.yml (src/main/resources/config.yml,
        // undo-cache-size: 0) and VoxelSniperCore's own copy (undo-cache-size: 20) at the identical
        // archive path "config.yml" - shadowJar's duplicatesStrategy = DuplicatesStrategy.INCLUDE
        // (set in Task 1) keeps both entries rather than failing the build over the clash.
        //
        // Building the real shadow jar and resolving "config.yml" through a URLClassLoader over it
        // showed getResourceAsStream returns *core's* copy (undo-cache-size: 20), not ours - despite
        // ours being listed first in the zip's central directory. Whichever config.yml VoxelSniper-
        // Configuration's constructor above extracted (or found already on disk from a previous run)
        // is therefore not something this code can trust.
        //
        // setUndoCacheSize is exposed by core specifically for this: force the in-memory value back
        // to 0 unconditionally, every start, regardless of which file won the race. This is what
        // FileHandlerTest's "the undo cache size is 0 even when a pre-existing config on disk says
        // otherwise" test guards.
        configuration.setUndoCacheSize(0)
    }

    override fun getFileHandler(): IFileHandler = handler
    override fun getVoxelSniperConfiguration(): VoxelSniperConfiguration = configuration
    override fun getLogger(): Logger = logger

    /**
     * The enum has SPIGOT, FORGE and FABRIC and no Minestom constant, and we consume core as a
     * published artifact rather than a fork. This is safe because `getEnvironment()` is declared on
     * IVoxelsniper and **called nowhere in VoxelSniperCore** - verified by grep over v8.14.0. If a
     * future core release starts branching on it, this becomes a real problem and the answer is a
     * patched core, not a different lie here.
     */
    override fun getEnvironment(): Environment = Environment.FABRIC

    override fun getPlayer(uuid: UUID): IPlayer? = MinestomPlayers.byUuid(uuid)
    override fun getPlayer(name: String): IPlayer? = MinestomPlayers.byName(name)
    override fun getOnlinePlayerNames(): List<String> = MinestomPlayers.all().map { it.name }

    /**
     * Straight through to the cached registry and nothing else: `VoxelMaterial.getMaterial` routes
     * every lookup here, including `VoxelMaterial.AIR()` from inside brush inner loops.
     */
    override fun getMaterial(namespace: String, key: String): VoxelMaterial? =
        MaterialRegistry.of(namespace, key)

    override fun getMaterials(): List<VoxelMaterial> = MaterialRegistry.all()

    // Biomes are read-only here: getBiome answers so brush parsing succeeds, and the write path
    // (BiomeOperation) is unguarded by decision - see spec section 6.
    override fun getBiome(namespace: String, key: String): VoxelBiome? = VoxelBiome(namespace, key)
    override fun getBiomes(): List<VoxelBiome> = emptyList()

    override fun getEntityType(namespace: String, key: String): VoxelEntityType? =
        VoxelEntityType(namespace, key)
    override fun getEntityTypes(): List<VoxelEntityType> = emptyList()

    // Minestom has no tree generator, so MinestomWorld.generateTree returns null and no tree type is
    // ever used. getDefaultTreeType must still answer non-null: core calls it during brush setup.
    override fun getTreeType(namespace: String, key: String): VoxelTreeType? =
        VoxelTreeType(namespace, key)
    override fun getDefaultTreeType(): VoxelTreeType = VoxelTreeType("minecraft", "oak")
    override fun getTreeTypes(): List<VoxelTreeType> = emptyList()
}
