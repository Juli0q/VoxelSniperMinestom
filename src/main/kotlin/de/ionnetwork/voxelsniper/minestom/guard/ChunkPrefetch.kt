package de.ionnetwork.voxelsniper.minestom.guard

/**
 * The chunks a brush is about to *read*, as one array for `EditTarget.ensureLoaded`.
 *
 * This is not the same job [SnipeGuard] does. The guard loads the exact bounds of a finished change
 * set, which covers the write phase precisely - but a brush reads the map to decide what to write
 * long before any operation list exists, and those reads have to land on loaded chunks too.
 *
 * "Have to" is literal. `BuildEditTarget.blockAt` is `instance.getBlock(x, y, z)` (read from the
 * shipped `MinestomConversion.jar` with `javap -c`), and Minestom's `Instance.getBlock` delegates to
 * a `ChunkCache` built with a *null* default block, then throws
 * `NullPointerException("Unloaded chunk at ...")` when the cache answers null. So a read of an
 * unloaded chunk does not quietly return air - it throws, out of the middle of a brush, and the
 * snipe writes nothing. Prefetching is what keeps a builder's brush from failing that way.
 *
 * Asking once for every chunk beats asking per block, which is also what `ensureLoaded`'s own
 * javadoc says: handed the set, the implementation starts every load and waits once.
 */
object ChunkPrefetch {

    /**
     * The packing `EditTarget.ensureLoaded` documents: `(chunkX << 32) | (chunkZ & 0xFFFFFFFF)`.
     * `BuildEditTarget.ensureLoaded` decodes it with `(key ushr 32).toInt()` and `key.toInt()`.
     */
    fun key(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    /**
     * Every chunk within [radius] blocks of ([centreX], [centreZ]).
     *
     * `Math.floorDiv` rather than `/`, because a build map's coordinates are routinely negative and
     * only flooring puts block -1 in chunk -1.
     *
     * **What this does not cover.** The caller centres it on the builder, because the block a brush
     * acts on is not known until core ray-traces for it. `BlockHelper` traces up to
     * `DEFAULT_RANGE = 250` blocks (VoxelSniperCore 8.14.0), so a builder aiming past this square at
     * a chunk nothing has loaded still reads an unloaded chunk. That is deliberately not solved by
     * widening the square: 250 blocks each way is ~1,000 chunks to load on every single click, on a
     * tick thread, and the failure it would prevent is a logged exception that writes nothing - the
     * over-fetch would be the worse of the two. The multi-block brushes (Canyon, Ocean, Extrude,
     * Move, CopyPasta, Pull, FlatOcean, CanyonSelection) reach past their brush size for the same
     * reason and are the same known gap (spec section 8).
     */
    fun keysFor(centreX: Int, centreZ: Int, radius: Int): LongArray {
        val minChunkX = Math.floorDiv(centreX - radius, 16)
        val maxChunkX = Math.floorDiv(centreX + radius, 16)
        val minChunkZ = Math.floorDiv(centreZ - radius, 16)
        val maxChunkZ = Math.floorDiv(centreZ + radius, 16)
        val keys = LongArray((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1))
        var i = 0
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                keys[i++] = key(chunkX, chunkZ)
            }
        }
        return keys
    }
}
