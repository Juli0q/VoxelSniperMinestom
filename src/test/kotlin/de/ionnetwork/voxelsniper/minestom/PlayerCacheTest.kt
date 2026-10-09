package de.ionnetwork.voxelsniper.minestom

import com.github.kevindagame.snipe.Sniper
import com.github.kevindagame.voxelsniper.entity.IEntity
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType
import com.github.kevindagame.voxelsniper.entity.player.IPlayer
import com.github.kevindagame.voxelsniper.location.BaseLocation
import com.github.kevindagame.voxelsniper.material.VoxelMaterial
import com.github.kevindagame.voxelsniper.vector.VoxelVector
import com.github.kevindagame.voxelsniper.world.IWorld
import de.ionnetwork.voxelsniper.minestom.entity.MinestomPlayers
import net.kyori.adventure.text.Component
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlayerCacheTest {

    @AfterTest
    fun clear() = MinestomPlayers.clear()

    @Test
    fun `an unknown uuid resolves to null rather than throwing`() {
        assertNull(MinestomPlayers.byUuid(UUID.randomUUID()))
        assertNull(MinestomPlayers.byName("nobody"))
    }

    @Test
    fun `leave forgets the player`() {
        val uuid = UUID.randomUUID()
        MinestomPlayers.put(uuid, FakePlayerEntry(uuid, "builder"))
        assertNotNull(MinestomPlayers.byUuid(uuid))
        MinestomPlayers.leave(uuid)
        assertNull(MinestomPlayers.byUuid(uuid))
        assertNull(MinestomPlayers.byName("builder"))
    }

    @Test
    fun `the same uuid resolves to the same IPlayer, so the Sniper survives`() {
        val uuid = UUID.randomUUID()
        val entry = FakePlayerEntry(uuid, "builder")
        MinestomPlayers.put(uuid, entry)
        assertSame(entry, MinestomPlayers.byUuid(uuid))
    }

    @Test
    fun `byName finds the player by their name, and is case sensitive like the uuid lookup`() {
        val entry = FakePlayerEntry(UUID.randomUUID(), "Builder")
        MinestomPlayers.put(entry.uniqueId, entry)
        assertSame(entry, MinestomPlayers.byName("Builder"))
        assertNull(MinestomPlayers.byName("builder"))
    }

    @Test
    fun `all lists every cached player, and leave shrinks it`() {
        val one = FakePlayerEntry(UUID.randomUUID(), "one")
        val two = FakePlayerEntry(UUID.randomUUID(), "two")
        MinestomPlayers.put(one.uniqueId, one)
        MinestomPlayers.put(two.uniqueId, two)
        assertEquals(setOf(one, two), MinestomPlayers.all().toSet())
        MinestomPlayers.leave(one.uniqueId)
        assertEquals(listOf<IPlayer>(two), MinestomPlayers.all())
    }

    @Test
    fun `leaving a uuid that was never cached is a no-op`() {
        MinestomPlayers.leave(UUID.randomUUID())
        assertEquals(emptyList(), MinestomPlayers.all())
    }
}

/**
 * Exists to test the cache, not the player: the cache only ever reads a uuid and a name, so those
 * are the only two methods with behaviour. Everything else throws rather than returning a plausible
 * lie, so that a future test which leans on this stub for something it was never meant to model
 * fails loudly instead of quietly passing.
 */
private class FakePlayerEntry(private val uuid: UUID, private val name: String) : IPlayer {

    override fun getUniqueId(): UUID = uuid
    override fun getName(): String = name

    override fun getSniper(): Sniper = notModelled()
    override fun sendMessage(message: String) = notModelled()
    override fun sendMessage(message: Component) = notModelled()
    override fun hasPermission(permissionNode: String): Boolean = notModelled()
    override fun isSneaking(): Boolean = notModelled()
    override fun teleport(location: BaseLocation) = notModelled()
    override fun launchProjectile(type: VoxelEntityType, velocity: VoxelVector): IEntity = notModelled()
    override fun getItemInHand(): VoxelMaterial = notModelled()

    override fun getType(): VoxelEntityType = notModelled()
    override fun remove() = notModelled()
    override fun getEntityId(): Int = notModelled()
    override fun getLocation(): BaseLocation = notModelled()
    override fun getWorld(): IWorld = notModelled()
    override fun addPassenger(entity: IEntity) = notModelled()
    override fun getPassengers(): List<IEntity> = notModelled()
    override fun getEyeLocation(): BaseLocation = notModelled()
    override fun getNearbyEntities(x: Int, y: Int, z: Int): List<IEntity> = notModelled()
    override fun eject() = notModelled()
    override fun teleport(other: IEntity): Boolean = notModelled()

    private fun notModelled(): Nothing =
        throw NotImplementedError("FakePlayerEntry models a cache entry's identity and nothing else")
}
