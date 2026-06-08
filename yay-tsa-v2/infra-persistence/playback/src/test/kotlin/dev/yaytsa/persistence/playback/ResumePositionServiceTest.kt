package dev.yaytsa.persistence.playback

import dev.yaytsa.application.playback.ResumeSource
import dev.yaytsa.application.playback.ResumeStatus
import dev.yaytsa.application.playback.ResumePositionService
import dev.yaytsa.application.playback.port.ResumePositionRepository
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class ResumePositionServiceTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: ResumePositionRepository

    private val service by lazy { ResumePositionService(repository) }

    private fun newUser() = UserId(UUID.randomUUID().toString())

    private fun newItem() = UUID.randomUUID().toString()

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `progress event persists resume position`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, positionMs = 30_000, runTimeMs = 100_000, sourceEvent = ResumeSource.PROGRESS, requestTime = t0)

        val saved = repository.find(uid, item)
        assertNotNull(saved)
        assertEquals(30_000, saved!!.positionMs)
        assertEquals(ResumeStatus.IN_PROGRESS, saved.status)
    }

    @Test
    fun `stopped event persists exact position`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, 30_000, 100_000, ResumeSource.PROGRESS, t0)
        service.record(uid, item, 45_000, 100_000, ResumeSource.STOPPED, t0.plusSeconds(60))

        assertEquals(45_000, repository.find(uid, item)!!.positionMs)
    }

    @Test
    fun `progress beyond completion threshold marks finished`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, 99_000, 100_000, ResumeSource.PROGRESS, t0)

        assertEquals(ResumeStatus.FINISHED, repository.find(uid, item)!!.status)
    }

    @Test
    fun `finished then restart becomes relistening with reset position`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, 99_000, 100_000, ResumeSource.PROGRESS, t0)
        assertEquals(ResumeStatus.FINISHED, repository.find(uid, item)!!.status)

        service.markFinished(uid, item, t0.plusSeconds(10))
        assertEquals(ResumeStatus.FINISHED, repository.find(uid, item)!!.status)

        service.restart(uid, item, t0.plusSeconds(20))
        val restarted = repository.find(uid, item)!!
        assertEquals(ResumeStatus.RELISTENING, restarted.status)
        assertEquals(0, restarted.positionMs)
    }

    @Test
    fun `stale progress does not overwrite newer meaningful position`() {
        val uid = newUser()
        val item = newItem()

        // Newer authoritative stop at 45s.
        service.record(uid, item, 45_000, 100_000, ResumeSource.STOPPED, t0.plusSeconds(120))
        // An older progress heartbeat (lower position, earlier time) arrives out of order.
        service.record(uid, item, 10_000, 100_000, ResumeSource.PROGRESS, t0.plusSeconds(30))

        assertEquals(45_000, repository.find(uid, item)!!.positionMs)
    }

    @Test
    fun `progress heartbeat only advances forward`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, 50_000, 100_000, ResumeSource.PROGRESS, t0.plusSeconds(10))
        // Later heartbeat with a lower position must not rewind (furthest-position-wins).
        service.record(uid, item, 20_000, 100_000, ResumeSource.PROGRESS, t0.plusSeconds(20))

        assertEquals(50_000, repository.find(uid, item)!!.positionMs)
    }

    @Test
    fun `deliberate backward seek is honored`() {
        val uid = newUser()
        val item = newItem()

        service.record(uid, item, 60_000, 100_000, ResumeSource.PROGRESS, t0.plusSeconds(10))
        // The user seeks back and stops there — an authoritative event must win even going backward.
        service.record(uid, item, 5_000, 100_000, ResumeSource.SEEK, t0.plusSeconds(20))

        assertEquals(5_000, repository.find(uid, item)!!.positionMs)
    }
}
