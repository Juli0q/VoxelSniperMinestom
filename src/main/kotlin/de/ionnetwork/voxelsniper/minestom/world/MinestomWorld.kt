package de.ionnetwork.voxelsniper.minestom.world

import com.github.kevindagame.util.brushOperation.BrushOperation
import com.github.kevindagame.voxelsniper.biome.VoxelBiome
import com.github.kevindagame.voxelsniper.block.IBlock
import com.github.kevindagame.voxelsniper.chunk.IChunk
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.treeType.VoxelTreeType
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlock
import java.util.UUID

/**
 * The map a player may currently edit, as VoxelSniper's notion of a world.
 *
 * Never per Minestom Instance, and never the instance the player happens to be standing in. That
 * distinction is the whole point of the guard: `EditGuard.targetFor` decides which map a player
 * edits, and everything here reads only that.
 *
 * **This holds a player, not a target**, and resolves the target on every access. `EditGuard`'s own
 * javadoc requires it: "Resolved per call rather than cached: a session's map, profile and role all
 * change during a visit (a profile switch, a reviewer enabling edit mode, a BuildSwap rotation), and
 * a cached target would keep answering with the old one." A world built once per connection and
 * handed a target at spawn would keep reading the map the builder started on for the rest of their
 * visit - silently, and into whatever map the guard had since moved them to.
 */
class MinestomWorld private constructor(
    private val owner: UUID?,
    private val fixed: EditTarget?
) : IWorld {

    /** The production shape: a player, whose map is looked up again on every read. */
    constructor(owner: UUID) : this(owner, null)

    /**
     * A world pinned to one target, for tests only.
     *
     * Pinning is exactly what `EditGuard.targetFor` forbids of production code, which is why this is
     * `internal`: a test that hands over a `FakeEditTarget` has no guard to resolve through and only
     * ever has one map, so there is nothing for it to go stale against.
     */
    internal constructor(target: EditTarget) : this(null, target)

    /**
     * The map to read, resolved now.
     *
     * Throws rather than substituting an empty world when there is no map to resolve. A null object
     * answering "air" would be worse than useless here: [SnipeGuard][de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard]
     * resolves its *own* target when the snipe event fires, so a brush that had computed against a
     * fabricated empty world would still have its result written - to a real map, from readings of
     * one that does not exist. Failing the read instead aborts the snipe before any operation list
     * exists, and the builder's map is left alone. The listener and the guard both check for a map
     * before a snipe starts, so reaching this means the session ended mid-snipe.
     */
    val target: EditTarget
        get() = fixed ?: resolve()

    private fun resolve(): EditTarget {
        val owner = owner ?: error("MinestomWorld has neither an owner to resolve nor a target to read")
        // Null on a server that is not a build node - there is no map anywhere to answer with.
        val guard = EditGuards.get()
            ?: error("This server is not a build node, so VoxelSniper has no map to read for $owner")
        guard.targetFor(owner)?.let { return it }

        // Tells the builder before throwing, because this is the only place that knows a read was
        // refused. A command that reads the map is otherwise refused silently: core's
        // `VoxelCommand.execute` catches the throw, prints a stack trace nobody can place and sends
        // its generic command-error message. This puts the build server's own reason - the same one
        // every other build-server command gives, in the builder's own language - in front of it.
        // Once per failure, not once per block: the throw ends the read that asked.
        guard.message(owner, guard.noTargetReason(owner), emptyMap())
        error("$owner has no editable map right now, so there is nothing for VoxelSniper to read")
    }

    override fun getBlock(location: BaseLocation): IBlock =
        getBlock(location.blockX, location.blockY, location.blockZ)

    override fun getBlock(x: Int, y: Int, z: Int): IBlock = MinestomBlock(this, x, y, z)

    override fun getMinWorldHeight(): Int = target.minY()
    override fun getMaxWorldHeight(): Int = target.maxY()

    override fun getChunkAtLocation(x: Int, z: Int): IChunk = MinestomChunk(this, x, z)

    override fun getName(): String = target.mapId()

    /**
     * Scans down from the top. Returns [getMinWorldHeight] for a column with nothing in it.
     *
     * Resolves the map once for the whole column rather than per block. That is still per call - what
     * `EditGuard.targetFor` forbids is holding one *between* calls - and it matters here because
     * `BuildEditGuard.targetFor` builds a fresh `BuildEditTarget` every time it is asked (read from
     * the shipped jar with `javap -c`), so a per-block resolution would allocate one per Y level.
     */
    override fun getHighestBlockYAt(x: Int, z: Int): Int {
        val map = target
        for (y in map.maxY() - 1 downTo map.minY()) {
            if (!map.blockAt(x, y, z).air()) return y
        }
        return map.minY()
    }

    override fun getBiome(location: BaseLocation): VoxelBiome = VoxelBiome("minecraft", "plains")

    /**
     * A no-op, confirmed as one in Task 11 rather than left pending.
     *
     * `EditTarget` hands out no `Instance` - deliberately, so that "an editing extension has no
     * business scheduling on it, loading its chunks or writing to it directly" - and `EditChange`
     * describes block writes only. There is therefore no path on which a biome write could be
     * checked, staged and committed, and the only Instance reachable from here is the one the player
     * happens to be standing in, which is by definition not the map being edited. Writing there
     * would corrupt a different world while bypassing every build-server rule.
     *
     * BiomeBrush and PolyBrush's biome property consequently do nothing. Restoring them means a
     * biome variant of `EditChange` in the SPI; Task 12 documents them as unavailable until then.
     */
    override fun setBiome(x: Int, y: Int, z: Int, selectedBiome: VoxelBiome) = Unit

    // --- Deliberately absent capabilities (spec section 10) -------------------------------------

    /** Minestom has no world generator, so there is no chunk to regenerate from. */
    override fun regenerateChunk(x: Int, z: Int) = Unit

    /** The batch commit resends the blocks it changed; there is no separate chunk refresh to do. */
    override fun refreshChunk(x: Int, z: Int) = Unit

    /** A world effect, not a block write, and not part of a build map's history. */
    override fun strikeLightning(location: BaseLocation) = Unit

    /**
     * Minestom ships no tree generator. Returning null - which IWorld explicitly permits - makes
     * TreeSnipeBrush emit no operations rather than fail. Note this is the *only* reason that brush
     * is unavailable: the operations generateTree returns are BlockOperations, so it would otherwise
     * be fully guarded.
     */
    override fun generateTree(
        location: BaseLocation,
        treeType: VoxelTreeType,
        updateBlocks: Boolean
    ): List<BrushOperation>? = null

    /** A no-op for the same reason as [setBiome]: no Instance, so nowhere to spawn anything. */
    override fun spawn(location: BaseLocation, entity: VoxelEntityType) = Unit

    /**
     * Empty, and not for want of trying: entities live on an `Instance` and `EditTarget` exposes
     * none, so there is no set of entities on the edited map to answer with. EntityBrush,
     * EntityRemovalBrush and JockeyBrush therefore find nothing (Task 12 documents them).
     *
     * If the SPI ever exposes them, whatever fills this in must resolve any player it finds through
     * [de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers]. JockeyBrush tests the results with
     * `instanceof IPlayer`, and a freshly constructed `MinestomPlayer` would mint a *second* `Sniper`
     * for a connection that already has one - splitting that builder's brush, brush size and
     * performer between two objects, with only one of them attached to their tool. The same applies
     * to [MinestomChunk.getEntities].
     */
    override fun getNearbyEntities(location: BaseLocation, x: Double, y: Double, z: Double): List<IEntity> =
        emptyList()
}
