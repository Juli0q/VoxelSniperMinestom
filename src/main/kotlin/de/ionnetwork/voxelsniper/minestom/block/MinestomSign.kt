package de.ionnetwork.voxelsniper.minestom.block

import com.github.kevindagame.voxelsniper.blockstate.sign.ISign
import net.minestom.server.instance.block.Block

/**
 * A sign, captured. `ISign` is [com.github.kevindagame.voxelsniper.blockstate.IBlockState] plus
 * per-line access - just [getLine]/[setLine], there is no `getLines()`/four-line array in the real
 * interface (the brief guessed one; this matches `ISign` as written).
 *
 * Sign text cannot be read off [captured]: verified against the resolved Minestom jar
 * (`minestom-master-SNAPSHOT.jar`, module `com.github.Minestom.Minestom`) that nowhere in it does a
 * `front_text`/`back_text` (modern) or `Text1`..`Text4` (legacy) tag exist. `EditSignListener` and
 * `ClientUpdateSignPacket` carry the four edited lines as a bare `List<String>` from the client's
 * packet straight into `PlayerEditSignEvent` - Minestom itself never persists them as block NBT or
 * a `BlockHandler` tag, that is left entirely to the consuming server. Nothing in this repo or
 * `minestom-extension-api` defines such a tag either, so there is no source of truth for this
 * adapter to read. Per the task's own escape hatch, this returns empty lines rather than fabricate
 * a read that looks like it works.
 *
 * [setLine] still records into an in-memory array, so a caller within the same brush pass observes
 * what it set - but since [MinestomBlockState.update] cannot write yet, nothing here reaches the
 * world, and the next capture of this position reads empty lines again.
 */
class MinestomSign(
    block: MinestomBlock,
    captured: Block
) : MinestomBlockState(block, captured), ISign {

    private val lines = arrayOf("", "", "", "")

    override fun getLine(line: Int): String = lines.getOrElse(line) { "" }

    override fun setLine(line: Int, signText: String) {
        if (line in lines.indices) lines[line] = signText
    }
}
