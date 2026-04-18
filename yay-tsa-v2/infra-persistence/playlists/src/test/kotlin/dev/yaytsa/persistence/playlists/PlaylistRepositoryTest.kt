package dev.yaytsa.persistence.playlists

import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistEntry
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PlaylistRepositoryTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: PlaylistRepository

    @Test
    fun `save and find round-trip with tracks`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = playlistAggregate(playlistId, owner, now)

        repository.save(aggregate)

        val found = repository.find(playlistId)
        assertNotNull(found)
        assertEquals(playlistId, found!!.id)
        assertEquals(owner, found.owner)
        assertEquals("My Playlist", found.name)
        assertEquals("A test playlist", found.description)
        assertEquals(false, found.isPublic)
        assertEquals(2, found.tracks.size)
        assertEquals(TrackId("track-a"), found.tracks[0].trackId)
        assertEquals(TrackId("track-b"), found.tracks[1].trackId)
    }

    @Test
    fun `find returns null for non-existent playlist`() {
        val found = repository.find(PlaylistId(UUID.randomUUID().toString()))
        assertNull(found)
    }

    @Test
    fun `findByOwner returns playlists for owner`() {
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val playlist1 = playlistAggregate(PlaylistId(UUID.randomUUID().toString()), owner, now)
        val playlist2 =
            playlistAggregate(PlaylistId(UUID.randomUUID().toString()), owner, now)
                .copy(name = "Second Playlist")

        repository.save(playlist1)
        repository.save(playlist2)

        val found = repository.findByOwner(owner)
        assertEquals(2, found.size)
    }

    @Test
    fun `delete removes playlist and tracks`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = playlistAggregate(playlistId, owner, now)

        repository.save(aggregate)
        assertNotNull(repository.find(playlistId))

        repository.delete(playlistId)
        assertNull(repository.find(playlistId))
    }

    @Test
    fun `update with OCC increments version`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = playlistAggregate(playlistId, owner, now)

        repository.save(aggregate)

        val loaded = repository.find(playlistId)!!
        assertEquals(AggregateVersion.INITIAL, loaded.version)

        val updated =
            loaded.copy(
                name = "Updated Name",
                version = loaded.version.next(),
            )
        repository.save(updated)

        val reloaded = repository.find(playlistId)!!
        assertEquals("Updated Name", reloaded.name)
        assertEquals(AggregateVersion(1), reloaded.version)
    }

    @Test
    fun `concurrent save with stale version is rejected`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = playlistAggregate(playlistId, owner, now)

        repository.save(aggregate)

        val loaded = repository.find(playlistId)!!

        // First update succeeds
        val update1 =
            loaded.copy(
                name = "First Update",
                version = loaded.version.next(),
            )
        repository.save(update1)

        // Second update with stale version should fail
        val staleUpdate =
            loaded.copy(
                name = "Stale Update",
                version = loaded.version.next(),
            )
        assertThrows<OptimisticLockException> {
            repository.save(staleUpdate)
        }
    }

    @Test
    fun `save empty playlist without tracks`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate =
            PlaylistAggregate(
                id = playlistId,
                owner = owner,
                name = "Empty Playlist",
                description = null,
                isPublic = true,
                tracks = emptyList(),
                createdAt = now,
                updatedAt = now,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(playlistId)
        assertNotNull(found)
        assertTrue(found!!.tracks.isEmpty())
        assertEquals(true, found.isPublic)
        assertNull(found.description)
    }

    @Test
    fun `findByOwner returns empty list for unknown user`() {
        val unknownUser = UserId(UUID.randomUUID().toString())
        val found = repository.findByOwner(unknownUser)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `save playlist with many tracks preserves order`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val tracks =
            (0 until 10).map { i ->
                PlaylistEntry(TrackId("track-$i"), now)
            }
        val aggregate =
            PlaylistAggregate(
                id = playlistId,
                owner = owner,
                name = "Big Playlist",
                description = "A playlist with many tracks",
                isPublic = false,
                tracks = tracks,
                createdAt = now,
                updatedAt = now,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(playlistId)
        assertNotNull(found)
        assertEquals(10, found!!.tracks.size)
        for (i in 0 until 10) {
            assertEquals(TrackId("track-$i"), found.tracks[i].trackId)
        }
    }

    @Test
    fun `delete also removes associated tracks`() {
        val playlistId = PlaylistId(UUID.randomUUID().toString())
        val owner = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = playlistAggregate(playlistId, owner, now)

        repository.save(aggregate)
        assertNotNull(repository.find(playlistId))
        assertEquals(2, repository.find(playlistId)!!.tracks.size)

        repository.delete(playlistId)

        // Playlist should be gone
        assertNull(repository.find(playlistId))

        // Re-create the same playlist with different tracks to confirm no orphaned track rows interfere
        val newAggregate =
            PlaylistAggregate(
                id = playlistId,
                owner = owner,
                name = "Recreated Playlist",
                description = null,
                isPublic = true,
                tracks = listOf(PlaylistEntry(TrackId("new-track"), now)),
                createdAt = now,
                updatedAt = now,
                version = AggregateVersion.INITIAL,
            )
        repository.save(newAggregate)

        val reloaded = repository.find(playlistId)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.tracks.size)
        assertEquals(TrackId("new-track"), reloaded.tracks[0].trackId)
    }

    private fun playlistAggregate(
        playlistId: PlaylistId,
        owner: UserId,
        now: Instant,
    ): PlaylistAggregate =
        PlaylistAggregate(
            id = playlistId,
            owner = owner,
            name = "My Playlist",
            description = "A test playlist",
            isPublic = false,
            tracks =
                listOf(
                    PlaylistEntry(TrackId("track-a"), now),
                    PlaylistEntry(TrackId("track-b"), now),
                ),
            createdAt = now,
            updatedAt = now,
            version = AggregateVersion.INITIAL,
        )
}
