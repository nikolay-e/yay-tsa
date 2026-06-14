package dev.yaytsa.persistence.playback

import dev.yaytsa.application.playback.SavedPlayQueueService
import dev.yaytsa.application.playback.port.SavedPlayQueueRepository
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class SavedPlayQueueServiceTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: SavedPlayQueueRepository

    private val service by lazy { SavedPlayQueueService(repository) }

    private fun newUser() = UserId(UUID.randomUUID().toString())

    private fun newTrack() = UUID.randomUUID().toString()

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `save then find round-trips ordered ids current and position`() {
        val uid = newUser()
        val a = newTrack()
        val b = newTrack()
        val c = newTrack()

        service.save(uid, listOf(a, b, c), currentTrackId = b, positionMs = 42_000, changedBy = "test", requestTime = t0)

        val saved = service.find(uid)
        assertNotNull(saved)
        assertEquals(listOf(a, b, c), saved!!.trackIds, "queue order must be preserved")
        assertEquals(b, saved.currentTrackId)
        assertEquals(42_000, saved.positionMs)
        assertEquals("test", saved.changedBy)
        assertEquals(t0, saved.changedAt)
    }

    @Test
    fun `find returns null when no queue saved`() {
        assertNull(service.find(newUser()))
    }

    @Test
    fun `second save overwrites the previous snapshot`() {
        val uid = newUser()
        val a = newTrack()
        val b = newTrack()
        val c = newTrack()

        service.save(uid, listOf(a, b), currentTrackId = a, positionMs = 1_000, changedBy = "first", requestTime = t0)
        service.save(uid, listOf(c), currentTrackId = c, positionMs = 5_000, changedBy = "second", requestTime = t0.plusSeconds(60))

        val saved = service.find(uid)!!
        assertEquals(listOf(c), saved.trackIds)
        assertEquals(c, saved.currentTrackId)
        assertEquals(5_000, saved.positionMs)
        assertEquals("second", saved.changedBy)
    }

    @Test
    fun `current not present in tracks is dropped`() {
        val uid = newUser()
        val a = newTrack()

        service.save(uid, listOf(a), currentTrackId = newTrack(), positionMs = 0, changedBy = null, requestTime = t0)

        assertNull(service.find(uid)!!.currentTrackId)
    }

    @Test
    fun `negative position is clamped to zero`() {
        val uid = newUser()
        val a = newTrack()

        service.save(uid, listOf(a), currentTrackId = a, positionMs = -10, changedBy = null, requestTime = t0)

        assertEquals(0, service.find(uid)!!.positionMs)
    }
}
