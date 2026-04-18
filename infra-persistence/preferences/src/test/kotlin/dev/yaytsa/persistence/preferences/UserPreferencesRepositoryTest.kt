package dev.yaytsa.persistence.preferences

import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.preferences.Favorite
import dev.yaytsa.domain.preferences.PreferenceContract
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserPreferencesRepositoryTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: UserPreferencesRepository

    @Test
    fun `save and find round-trip with favorites and contract`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = preferencesAggregate(userId, now)

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertEquals(userId, found!!.userId)
        assertEquals(2, found.favorites.size)
        assertEquals(TrackId("track-1"), found.favorites[0].trackId)
        assertEquals(TrackId("track-2"), found.favorites[1].trackId)
        assertNotNull(found.preferenceContract)
        assertEquals("no-metal", found.preferenceContract!!.hardRules)
        assertEquals("chill", found.preferenceContract!!.softPrefs)
        assertEquals("smooth", found.preferenceContract!!.djStyle)
        assertEquals("no-screamo", found.preferenceContract!!.redLines)
    }

    @Test
    fun `find returns null for non-existent user`() {
        val found = repository.find(UserId(UUID.randomUUID().toString()))
        assertNull(found)
    }

    @Test
    fun `save empty aggregate without favorites or contract`() {
        val userId = UserId(UUID.randomUUID().toString())
        val aggregate = UserPreferencesAggregate.empty(userId)

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertEquals(0, found!!.favorites.size)
        assertNull(found.preferenceContract)
    }

    @Test
    fun `update with OCC increments version`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = preferencesAggregate(userId, now)

        repository.save(aggregate)

        val loaded = repository.find(userId)!!
        assertEquals(AggregateVersion.INITIAL, loaded.version)

        val updated =
            loaded.copy(
                favorites = loaded.favorites + Favorite(TrackId("track-3"), now, 2),
                version = loaded.version.next(),
            )
        repository.save(updated)

        val reloaded = repository.find(userId)!!
        assertEquals(3, reloaded.favorites.size)
        assertEquals(AggregateVersion(1), reloaded.version)
    }

    @Test
    fun `concurrent save with stale version is rejected`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = preferencesAggregate(userId, now)

        repository.save(aggregate)

        val loaded = repository.find(userId)!!

        // First update succeeds
        val update1 =
            loaded.copy(
                favorites = emptyList(),
                version = loaded.version.next(),
            )
        repository.save(update1)

        // Second update with stale version should fail
        val staleUpdate =
            loaded.copy(
                favorites = emptyList(),
                version = loaded.version.next(),
            )
        assertThrows<OptimisticLockException> {
            repository.save(staleUpdate)
        }
    }

    @Test
    fun `save with preference contract round-trip`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val contract =
            PreferenceContract(
                hardRules = "no-loud-genres",
                softPrefs = "prefer-acoustic",
                djStyle = "laid-back",
                redLines = "no-explicit-content",
                updatedAt = now,
            )
        val aggregate =
            UserPreferencesAggregate(
                userId = userId,
                favorites = emptyList(),
                preferenceContract = contract,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertNotNull(found!!.preferenceContract)
        assertEquals("no-loud-genres", found.preferenceContract!!.hardRules)
        assertEquals("prefer-acoustic", found.preferenceContract!!.softPrefs)
        assertEquals("laid-back", found.preferenceContract!!.djStyle)
        assertEquals("no-explicit-content", found.preferenceContract!!.redLines)
        assertEquals(now, found.preferenceContract!!.updatedAt)
    }

    @Test
    fun `favorites ordering is preserved`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val favorites =
            (0 until 5).map { i ->
                Favorite(TrackId("track-$i"), now, i)
            }
        val aggregate =
            UserPreferencesAggregate(
                userId = userId,
                favorites = favorites,
                preferenceContract = null,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertEquals(5, found!!.favorites.size)
        for (i in 0 until 5) {
            assertEquals(TrackId("track-$i"), found.favorites[i].trackId)
            assertEquals(i, found.favorites[i].position)
        }
    }

    @Test
    fun `update preference contract preserves favorites`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = preferencesAggregate(userId, now)

        repository.save(aggregate)

        val loaded = repository.find(userId)!!
        val updatedContract =
            PreferenceContract(
                hardRules = "updated-hard-rules",
                softPrefs = "updated-soft-prefs",
                djStyle = "updated-dj-style",
                redLines = "updated-red-lines",
                updatedAt = now.plus(1, ChronoUnit.HOURS),
            )
        val updated =
            loaded.copy(
                preferenceContract = updatedContract,
                version = loaded.version.next(),
            )
        repository.save(updated)

        val reloaded = repository.find(userId)!!
        // Favorites should still be intact
        assertEquals(2, reloaded.favorites.size)
        assertEquals(TrackId("track-1"), reloaded.favorites[0].trackId)
        assertEquals(TrackId("track-2"), reloaded.favorites[1].trackId)
        // Contract should be updated
        assertEquals("updated-hard-rules", reloaded.preferenceContract!!.hardRules)
        assertEquals("updated-dj-style", reloaded.preferenceContract!!.djStyle)
    }

    private fun preferencesAggregate(
        userId: UserId,
        now: Instant,
    ): UserPreferencesAggregate =
        UserPreferencesAggregate(
            userId = userId,
            favorites =
                listOf(
                    Favorite(TrackId("track-1"), now, 0),
                    Favorite(TrackId("track-2"), now, 1),
                ),
            preferenceContract =
                PreferenceContract(
                    hardRules = "no-metal",
                    softPrefs = "chill",
                    djStyle = "smooth",
                    redLines = "no-screamo",
                    updatedAt = now,
                ),
            version = AggregateVersion.INITIAL,
        )
}
