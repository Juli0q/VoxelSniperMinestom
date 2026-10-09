package de.ionnetwork.voxelsniper.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditGuard
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import java.util.UUID

/**
 * An EditGuard whose answer can change between calls, which is the whole point.
 *
 * `EditGuard.targetFor` is documented as "resolved per call rather than cached: a session's map,
 * profile and role all change during a visit (a profile switch, a reviewer enabling edit mode, a
 * BuildSwap rotation), and a cached target would keep answering with the old one". Nothing that
 * holds a target can be tested against a fake that only ever has one, so this one lets a test move
 * the map out from under a caller and see whether the caller noticed.
 */
class FakeEditGuard : EditGuard {

    /** The current answer per player. Absent and explicitly-null both mean "may not edit". */
    val targets = mutableMapOf<UUID, EditTarget?>()

    /** Every uuid [targetFor] was asked about, in order, so a test can assert *whose* map was resolved. */
    val asked = mutableListOf<UUID>()

    val messages = mutableListOf<Pair<UUID, String>>()

    var reason: String = "buildserver.edit.no_session"

    override fun targetFor(player: UUID): EditTarget? {
        asked += player
        return targets[player]
    }

    override fun noTargetReason(player: UUID): String = reason

    override fun message(player: UUID, key: String, args: Map<String, String>) {
        messages += player to key
    }
}
