package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.util.brushOperation.BiomeOperation
import com.github.kevindagame.util.brushOperation.BlockOperation
import com.github.kevindagame.util.brushOperation.BrushOperation
import com.github.kevindagame.voxelsniper.biome.VoxelBiome
import com.github.kevindagame.voxelsniper.location.BaseLocation
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.voxelsniper.minestom.block.MinestomBlockData
import de.ionnetwork.voxelsniper.minestom.guard.SnipeGuard
import de.ionnetwork.voxelsniper.minestom.world.MinestomWorld
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnipeGuardTest {

    private val target = FakeEditTarget()
    private val world = MinestomWorld(target)

    private fun at(x: Int, y: Int, z: Int) = BaseLocation(world, x.toDouble(), y.toDouble(), z.toDouble())

    private fun blockOp(x: Int, y: Int, z: Int, block: Block = Block.STONE) =
        BlockOperation(at(x, y, z), MinestomBlockData(Block.AIR), MinestomBlockData(block))

    private fun biomeOp(x: Int, y: Int, z: Int) =
        BiomeOperation(at(x, y, z), VoxelBiome("minecraft", "plains"), VoxelBiome("minecraft", "desert"))

    @Test
    fun `an accepted change set is staged and committed exactly once`() {
        val operations = listOf(blockOp(0, 64, 0), blockOp(1, 64, 0))
        val outcome = SnipeGuard.guard(target, operations, "ball")

        assertIs<SnipeGuard.Outcome.Applied>(outcome)
        assertEquals(2, outcome.blocks)
        assertEquals(1, target.beginCount)
        assertEquals(1, target.staged.size)
        assertEquals(listOf(2), target.commits)
        assertEquals(0, target.abortCount)
    }

    @Test
    fun `accepted block operations are cancelled so core never writes them`() {
        val operations = listOf(blockOp(0, 64, 0), blockOp(1, 64, 0))
        SnipeGuard.guard(target, operations, "ball")
        assertTrue(operations.all { it.isCancelled }, "core's write loop must skip every staged block")
    }

    @Test
    fun `a refused change set writes nothing at all`() {
        target.rejection = EditRejection.of("buildserver.edit.blocked")
        val operations = listOf(blockOp(0, 64, 0), blockOp(1, 64, 0))

        val outcome = SnipeGuard.guard(target, operations, "ball")

        assertIs<SnipeGuard.Outcome.Rejected>(outcome)
        assertTrue(target.staged.isEmpty(), "a refused edit stages no slice")
        assertTrue(target.commits.isEmpty(), "a refused edit commits nothing")
        // The edit is opened before the check and thrown away after it: `check` accumulates into
        // the target's per-edit block counter and does not roll that back when it rejects, so
        // `beginEdit`/`abortEdit` are what keep a refusal from leaving the counter dirty.
        assertEquals(1, target.beginCount)
        assertEquals(1, target.abortCount)
        assertTrue(operations.all { it.isCancelled }, "refused blocks must not fall through to core")
    }

    @Test
    fun `a refusal is reported through the build server's own message path`() {
        target.rejection = EditRejection.of("buildserver.edit.blocked")
        SnipeGuard.guard(target, listOf(blockOp(0, 64, 0)), "ball")
        assertEquals(listOf("buildserver.edit.blocked"), target.messages)
    }

    /**
     * A contract regression test, not a reproduction of a reachable outage.
     *
     * `BuildEditTarget.check` does `staged += changes.size()` *before* testing the cap and does not
     * roll it back when it rejects; only `beginEdit`/`commitEdit`/`abortEdit` zero it (verified by
     * `javap -c` on the shipped `MinestomConversion.jar`). A guard that calls `check` outside a
     * `beginEdit`/`abortEdit` bracket would leave every refusal's block count behind, and once the
     * sum crosses the cap the target would refuse legal edits forever with no visible cause.
     *
     * On today's adapter that outage is not actually reachable: `BuildEditGuard.targetFor` builds a
     * fresh `BuildEditTarget` on every call, and `SnipeGuard.onSnipe` resolves the target per snipe,
     * so the counter cannot survive between snipes to drift. What this test guards is the contract
     * itself - `EditTarget` does not forbid a caller from holding one target across several `check`
     * calls, and the bracket is what keeps that future caller safe. It is worth keeping precisely
     * because nothing else here would catch its absence.
     */
    @Test
    fun `refused snipes do not drift the target's block counter into a permanent refusal`() {
        val tight = FakeEditTarget(maxBlocks = 8)
        val tightWorld = MinestomWorld(tight)
        fun ops() = (0 until 3).map {
            BlockOperation(
                BaseLocation(tightWorld, it.toDouble(), 64.0, 0.0),
                MinestomBlockData(Block.AIR),
                MinestomBlockData(Block.STONE)
            )
        }

        tight.rejection = EditRejection.of("buildserver.edit.blocked")
        repeat(4) {
            assertIs<SnipeGuard.Outcome.Rejected>(SnipeGuard.guard(tight, ops(), "ball"))
            assertEquals(0, tight.stagedBlocks, "a refused snipe must leave the counter where it found it")
        }

        tight.rejection = null
        val outcome = SnipeGuard.guard(tight, ops(), "ball")
        assertIs<SnipeGuard.Outcome.Applied>(
            outcome,
            "12 blocks of refusals must not have spent the 8-block budget a legal snipe needs"
        )
    }

    @Test
    fun `a committed snipe leaves the block counter clean for the next one`() {
        repeat(3) { SnipeGuard.guard(target, listOf(blockOp(it, 64, 0)), "ball") }
        assertEquals(0, target.stagedBlocks)
        assertEquals(3, target.beginCount)
        assertEquals(listOf(1, 1, 1), target.commits)
    }

    @Test
    fun `non-block operations are left alive for core to execute`() {
        val block = blockOp(0, 64, 0)
        val biome = biomeOp(0, 64, 0)
        val operations = listOf<BrushOperation>(block, biome)

        SnipeGuard.guard(target, operations, "biome")

        assertTrue(block.isCancelled, "block writes go through the guard")
        assertFalse(biome.isCancelled, "biome writes stay on core's path - unguarded, by decision")
    }

    @Test
    fun `non-block operations survive even when the block half is refused`() {
        target.rejection = EditRejection.of("buildserver.edit.blocked")
        val block = blockOp(0, 64, 0)
        val biome = biomeOp(0, 64, 0)

        SnipeGuard.guard(target, listOf(block, biome), "biome")

        assertTrue(block.isCancelled)
        assertFalse(biome.isCancelled)
    }

    @Test
    fun `a brush with no block operations does nothing and opens no edit`() {
        val outcome = SnipeGuard.guard(target, listOf(biomeOp(0, 64, 0)), "biome")
        assertIs<SnipeGuard.Outcome.NothingToDo>(outcome)
        assertEquals(0, target.beginCount)
        assertEquals(0, target.abortCount)
        assertTrue(target.staged.isEmpty())
    }

    @Test
    fun `progress is reported with the brush name and the real block count`() {
        SnipeGuard.guard(target, listOf(blockOp(0, 64, 0), blockOp(0, 64, 0)), "ball")
        assertEquals(1, target.progress.size)
        assertEquals("ball", target.progress[0].command())
        assertEquals(1L, target.progress[0].blocks(), "a repeated position is one block, not two")
        assertEquals(1L, target.progress[0].totalBlocks())
        assertTrue(
            target.progress[0].hasTotal(),
            "the action bar only stops showing a finished edit when blocks reaches an honest total"
        )
    }

    /**
     * `BuildEditActionBar.report` only stores a non-null report and drops any pending linger; the
     * `hasTotal() && blocks >= totalBlocks` test that ends the display lives on its `progress == null`
     * branch, and nothing else on the server ever clears it (verified by `javap -c` on the shipped
     * `MinestomConversion.jar`). Without the null call a builder's first snipe pins the action bar
     * for the rest of their session.
     */
    @Test
    fun `the action bar is cleared once the applied edit has been reported`() {
        SnipeGuard.guard(target, listOf(blockOp(0, 64, 0)), "ball")

        assertEquals(2, target.progressCalls.size, "one report, then the clear that ends it")
        assertEquals("ball", target.progressCalls[0]?.command())
        assertNull(target.progressCalls[1], "reportProgress(null) is what takes it off the action bar")
    }

    /**
     * A batch applied to an unloaded chunk is not applied at all - Minestom logs a warning and skips
     * it - so the writes would vanish while `Applied` and the progress report both still claimed them.
     */
    @Test
    fun `every chunk under the change set is loaded before it is staged`() {
        val operations = listOf(blockOp(-1, 64, -1), blockOp(0, 64, 0), blockOp(31, 64, 31))

        SnipeGuard.guard(target, operations, "ball")

        val expected = (-1..1).flatMap { x -> (-1..1).map { z -> x to z } }.toSet()
        assertEquals(expected, target.loadedChunks(), "the change set's whole bounding box, negatives included")
    }

    @Test
    fun `a refused change set loads no chunks at all`() {
        target.rejection = EditRejection.of("buildserver.edit.blocked")

        SnipeGuard.guard(target, listOf(blockOp(0, 64, 0)), "ball")

        assertTrue(target.loaded.isEmpty(), "a refused edit must not load a chunk for a write it will not make")
    }

    /**
     * `commitEdit`'s callback runs on the instance scheduler's next tick, not inline (verified by
     * `javap -c` on `EditEngine.applyPrebuilt`), so the count it reports is not available by the
     * time `guard` returns. The outcome therefore carries the size of the change set handed over.
     */
    @Test
    fun `the outcome counts the change set handed over even when the commit callback is deferred`() {
        val deferred = FakeEditTarget()
        deferred.deferCommitCallback = true
        val deferredWorld = MinestomWorld(deferred)
        val operations = listOf(
            BlockOperation(
                BaseLocation(deferredWorld, 0.0, 64.0, 0.0),
                MinestomBlockData(Block.AIR),
                MinestomBlockData(Block.STONE)
            )
        )

        val outcome = SnipeGuard.guard(deferred, operations, "ball")

        assertIs<SnipeGuard.Outcome.Applied>(outcome)
        assertEquals(1, outcome.blocks)
        assertTrue(deferred.progress.isEmpty(), "nothing is reported until the commit actually runs")
        deferred.runDeferredCommits()
        assertEquals(1, deferred.progress.size)
    }

    /**
     * Unreachable in practice - `MinestomBlockData` is the only `IBlockData` this adapter makes -
     * but the one failure that must not be silent is a block reaching core's write loop unchecked.
     */
    @Test
    fun `a block operation the guard cannot describe is cancelled rather than left to core`() {
        val foreign = BlockOperation(at(0, 64, 0), MinestomBlockData(Block.AIR), ForeignBlockData())

        val outcome = SnipeGuard.guard(target, listOf(foreign), "ball")

        assertIs<SnipeGuard.Outcome.NothingToDo>(outcome)
        assertTrue(foreign.isCancelled, "an undescribable block write must not fall through to core")
        assertEquals(0, target.beginCount)
    }

    @Test
    fun `an edit is never left open when the target throws`() {
        val exploding = FakeEditTarget()
        exploding.failOnStage = IllegalStateException("map thread gone")
        val explodingWorld = MinestomWorld(exploding)
        val operation = BlockOperation(
            BaseLocation(explodingWorld, 0.0, 64.0, 0.0),
            MinestomBlockData(Block.AIR),
            MinestomBlockData(Block.STONE)
        )

        val failure = runCatching { SnipeGuard.guard(exploding, listOf(operation), "ball") }

        assertTrue(failure.isFailure)
        assertEquals(1, exploding.abortCount, "an edit left open would hold the batch for the session")
        assertEquals(0, exploding.stagedBlocks)
        assertTrue(operation.isCancelled, "a block the guard did not write must not fall through to core")
        assertTrue(
            exploding.loaded.isNotEmpty(),
            "chunks are loaded before the stage that needs them - this fake never reached its stage"
        )
    }
}

/** An `IBlockData` from no platform at all, to drive the undescribable-write path. */
private class ForeignBlockData : com.github.kevindagame.voxelsniper.blockdata.IBlockData {
    override fun getMaterial(): com.github.kevindagame.voxelsniper.material.VoxelMaterial =
        error("not used")
    override fun matches(blockData: com.github.kevindagame.voxelsniper.blockdata.IBlockData?): Boolean = false
    override fun getAsString(): String = "foreign"
    override fun merge(other: com.github.kevindagame.voxelsniper.blockdata.IBlockData?): com.github.kevindagame.voxelsniper.blockdata.IBlockData = this
    override fun getCopy(): com.github.kevindagame.voxelsniper.blockdata.IBlockData = this
}
