package de.ionnetwork.voxelsniper.minestom.guard

import com.github.kevindagame.util.brushOperation.BlockOperation
import de.ionnetwork.minestomconversion.extension.edit.EditChanges
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minestom.server.instance.block.Block

/**
 * The block writes of one snipe, as the build server's change set.
 *
 * Built from the brush's own [BlockOperation] list, densely: position packed into a `long`, block
 * stored as the interned registry instance it already is. Duplicate positions collapse with the last
 * write winning, which is both what the applied batch would have done anyway and what makes
 * [size] honest - it is compared against `EditTarget.maxBlocks()`, so an over-count refuses edits
 * that are really in budget. [distinctBlocks] is derived from that same resolved map for the same
 * reason: a state overwritten before commit was never written, so it must not be reported.
 *
 * No [de.ionnetwork.minestomconversion.extension.edit.EditChanges] element objects are allocated.
 */
class SnipeChanges(operations: List<BlockOperation>) : EditChanges {

    private val writes = Long2ObjectOpenHashMap<Block>(operations.size)

    // Named `lowX`/`highX` etc, not `minX`/`maxX`: a same-named private property alongside
    // `override fun minX(): Int` would compile (unqualified `minX` means the property, `minX()`
    // the function), but that is a trap for the next reader - see the same call in
    // `FakeEditTarget`.
    private var lowX = Int.MAX_VALUE
    private var lowY = Int.MAX_VALUE
    private var lowZ = Int.MAX_VALUE
    private var highX = Int.MIN_VALUE
    private var highY = Int.MIN_VALUE
    private var highZ = Int.MIN_VALUE

    init {
        for (operation in operations) {
            val block = (operation.newData as? MinestomBlockData)?.block ?: continue
            val x = operation.location.blockX
            val y = operation.location.blockY
            val z = operation.location.blockZ
            writes.put(pack(x, y, z), block)
            if (x < lowX) lowX = x
            if (y < lowY) lowY = y
            if (z < lowZ) lowZ = z
            if (x > highX) highX = x
            if (y > highY) highY = y
            if (z > highZ) highZ = z
        }
    }

    // Derived from the *surviving* writes, after the loop above has resolved every overwrite -
    // not accumulated per-operation. EditChanges's own javadoc is explicit that this is "the
    // distinct block states written"; a state an ErodeBrush sweep lays down and then immediately
    // overwrites at the same cell was never written to the world and must not appear here, for the
    // same reason `size()` must not over-count that cell.
    private val distinct: Set<Block> = LinkedHashSet(writes.values)

    fun isEmpty(): Boolean = writes.isEmpty()

    override fun size(): Int = writes.size

    override fun forEach(visitor: EditChanges.Visitor) {
        val iterator = writes.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val packed = entry.longKey
            visitor.visit(unpackX(packed), unpackY(packed), unpackZ(packed), entry.value)
        }
    }

    override fun distinctBlocks(): Set<Block> = distinct

    override fun minX(): Int = lowX
    override fun minY(): Int = lowY
    override fun minZ(): Int = lowZ
    override fun maxX(): Int = highX
    override fun maxY(): Int = highY
    override fun maxZ(): Int = highZ

    internal companion object {
        /**
         * Minecraft's own block-position packing: 26 bits of x, 26 of z, 12 of y. Identical to
         * WorldEditMinestom's EditBuffer, deliberately - it is the shape everything else uses.
         */
        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        /** Sign-extended: a build map's coordinates are routinely negative. */
        fun unpackX(packed: Long): Int = signExtend((packed shr 38).toInt(), 26)
        fun unpackY(packed: Long): Int = signExtend((packed and 0xFFF).toInt(), 12)
        fun unpackZ(packed: Long): Int = signExtend(((packed shl 26) shr 38).toInt(), 26)

        private fun signExtend(value: Int, bits: Int): Int {
            val shift = 32 - bits
            return (value shl shift) shr shift
        }
    }
}
