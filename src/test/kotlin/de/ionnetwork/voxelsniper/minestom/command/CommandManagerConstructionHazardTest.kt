package de.ionnetwork.voxelsniper.minestom.command

import com.github.kevindagame.command.VoxelCommand
import com.github.kevindagame.command.VoxelCommandManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Demonstrates, against the real (unmodified) [VoxelCommandManager] from VoxelSniperCore, the
 * construction hazard [MinestomCommandManager] has to handle: that superclass's constructor calls
 * the abstract `registerCommand` twelve times *during `super()`*, before any of a Kotlin subclass's
 * own property initializers have run.
 *
 * [NaiveProbe] uses the pattern the task brief originally suggested for the registration list - a
 * property initialized inline - and it does not merely end up empty: `registerCommand`'s first call
 * happens while the field still holds the JVM default (`null`, before the subclass's own initializer
 * has run), so `seen += ...` NPEs immediately and the exception propagates out of `super()`, failing
 * construction outright.
 *
 * [FixedProbe] mirrors [MinestomCommandManager]'s actual, current shape for *both* fields the real
 * class has to get right, not just the one [NaiveProbe] covers: `seen` is a `lateinit var` with no
 * initializer expression (as in production), and the skip branch - which always fires once, for
 * `VoxelUndoCommand`'s `"u"`, the ninth of twelve `registerCommand` calls made from inside `super()`
 * - reads a companion-object `LOG` rather than an instance property. That second part was added
 * after a review caught that `MinestomCommandManager`'s first version got `registered` right and
 * missed it: a `private val log = LoggerFactory.getLogger(...)` instance property, read from that
 * same skip branch, NPE'd out of `super()` on every single construction. Temporarily reproducing
 * that exact shape inside `FixedProbe` (an instance property read from the skip branch, in place of
 * the companion `LOG` used below) reproduces the same `NullPointerException` here, confirming this
 * test now actually covers the shape that broke production, before the fix that keeps it green.
 */
class CommandManagerConstructionHazardTest {

    private class NaiveProbe : VoxelCommandManager() {
        val seen = mutableListOf<String>()

        override fun registerCommand(command: VoxelCommand) {
            if (command.identifier == "u") return
            seen += command.identifier
        }
    }

    private class FixedProbe : VoxelCommandManager() {
        private lateinit var seen: MutableList<String>

        override fun registerCommand(command: VoxelCommand) {
            if (command.identifier == "u") {
                // Mirrors MinestomCommandManager's skip branch, which reads a logger here: a
                // companion-object value, not an instance property, precisely because this branch
                // runs from inside super() - see LOG below and the class doc.
                check(LOG.isNotEmpty())
                return
            }
            if (!::seen.isInitialized) seen = mutableListOf()
            seen += command.identifier
        }

        fun seenIdentifiers(): List<String> = if (::seen.isInitialized) seen else emptyList()

        private companion object {
            // Companion objects are initialized once, when the class is first loaded - always
            // before any instance of it can exist - so this is never JVM-default `null` when
            // registerCommand's skip branch reads it during super(). An instance-level `private
            // val LOG = "probe-log"` here instead reproduces the exact bug found in
            // MinestomCommandManager's real `log` field: see the failing version of this test
            // that preceded this fix.
            val LOG = "probe-log"
        }
    }

    @Test
    fun `an inline-initialized property NPEs when the superclass constructor uses it first`() {
        assertFailsWith<NullPointerException> { NaiveProbe() }
    }

    @Test
    fun `a lateinit property with no initializer survives the superclass constructor`() {
        val probe = FixedProbe()
        // Twelve commands registered by VoxelCommandManager(), minus the one ("u") withheld.
        assertEquals(11, probe.seenIdentifiers().size)
        assertTrue("u" !in probe.seenIdentifiers())
    }
}
