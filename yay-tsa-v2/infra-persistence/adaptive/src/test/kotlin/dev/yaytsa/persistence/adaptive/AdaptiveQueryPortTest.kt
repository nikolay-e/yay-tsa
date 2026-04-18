package dev.yaytsa.persistence.adaptive

import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.persistence.adaptive.adapter.JpaAdaptiveQueryPort
import dev.yaytsa.persistence.adaptive.entity.AdaptiveQueueEntryEntity
import dev.yaytsa.persistence.adaptive.entity.ListeningSessionEntity
import dev.yaytsa.persistence.adaptive.entity.LlmDecisionEntity
import dev.yaytsa.persistence.adaptive.entity.PlaybackSignalEntity
import dev.yaytsa.persistence.adaptive.jpa.AdaptiveQueueEntryJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.ListeningSessionJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.LlmDecisionJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.PlaybackSignalJpaRepository
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(JpaAdaptiveQueryPort::class)
class AdaptiveQueryPortTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var port: JpaAdaptiveQueryPort

    @Autowired
    lateinit var sessionJpa: ListeningSessionJpaRepository

    @Autowired
    lateinit var queueJpa: AdaptiveQueueEntryJpaRepository

    @Autowired
    lateinit var signalJpa: PlaybackSignalJpaRepository

    @Autowired
    lateinit var decisionJpa: LlmDecisionJpaRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `findActiveSession returns session with no ended_at`() {
        val userId = UUID.randomUUID()
        val session = sessionEntity(userId = userId, endedAt = null)
        sessionJpa.saveAndFlush(session)

        val result = port.findActiveSession(UserId(userId.toString()))

        assertNotNull(result)
        assertEquals(session.id.toString(), result!!.id.value)
        assertEquals(userId.toString(), result.userId.value)
    }

    @Test
    fun `findActiveSession returns null when no active session`() {
        val userId = UUID.randomUUID()
        val session = sessionEntity(userId = userId, endedAt = now)
        sessionJpa.saveAndFlush(session)

        val result = port.findActiveSession(UserId(userId.toString()))

        assertNull(result)
    }

    @Test
    fun `findSession returns session by id`() {
        val sessionId = UUID.randomUUID()
        val session = sessionEntity(id = sessionId)
        sessionJpa.saveAndFlush(session)

        val result = port.findSession(ListeningSessionId(sessionId.toString()))

        assertNotNull(result)
        assertEquals(sessionId.toString(), result!!.id.value)
        assertEquals("background", result.attentionMode)
    }

    @Test
    fun `getQueueEntries returns entries ordered by position`() {
        val sessionId = UUID.randomUUID()
        sessionJpa.saveAndFlush(sessionEntity(id = sessionId))

        val entry1 = queueEntryEntity(sessionId = sessionId, position = 2)
        val entry2 = queueEntryEntity(sessionId = sessionId, position = 0)
        val entry3 = queueEntryEntity(sessionId = sessionId, position = 1)
        queueJpa.saveAllAndFlush(listOf(entry1, entry2, entry3))

        val result = port.getQueueEntries(ListeningSessionId(sessionId.toString()))

        assertEquals(3, result.size)
        assertEquals(0, result[0].position)
        assertEquals(1, result[1].position)
        assertEquals(2, result[2].position)
    }

    @Test
    fun `getSignals returns limited results ordered by createdAt desc`() {
        val sessionId = UUID.randomUUID()
        sessionJpa.saveAndFlush(sessionEntity(id = sessionId))

        val signal1 = signalEntity(sessionId = sessionId, createdAt = now.minusSeconds(30))
        val signal2 = signalEntity(sessionId = sessionId, createdAt = now.minusSeconds(10))
        val signal3 = signalEntity(sessionId = sessionId, createdAt = now)
        signalJpa.saveAllAndFlush(listOf(signal1, signal2, signal3))

        val result = port.getSignals(ListeningSessionId(sessionId.toString()), limit = 2)

        assertEquals(2, result.size)
        assertEquals(signal3.id.toString(), result[0].id)
        assertEquals(signal2.id.toString(), result[1].id)
    }

    @Test
    fun `getDecisions returns limited results ordered by createdAt desc`() {
        val sessionId = UUID.randomUUID()
        sessionJpa.saveAndFlush(sessionEntity(id = sessionId))

        val decision1 = decisionEntity(sessionId = sessionId, createdAt = now.minusSeconds(30))
        val decision2 = decisionEntity(sessionId = sessionId, createdAt = now.minusSeconds(10))
        val decision3 = decisionEntity(sessionId = sessionId, createdAt = now)
        decisionJpa.saveAllAndFlush(listOf(decision1, decision2, decision3))

        val result = port.getDecisions(ListeningSessionId(sessionId.toString()), limit = 2)

        assertEquals(2, result.size)
        assertEquals(decision3.id.toString(), result[0].id)
        assertEquals(decision2.id.toString(), result[1].id)
    }

    private fun sessionEntity(
        id: UUID = UUID.randomUUID(),
        userId: UUID = UUID.randomUUID(),
        endedAt: Instant? = null,
    ) = ListeningSessionEntity(
        id = id,
        userId = userId,
        state = "ACTIVE",
        startedAt = now,
        lastActivityAt = now,
        endedAt = endedAt,
        sessionSummary = null,
        energy = 0.8f,
        intensity = 0.5f,
        moodTags = listOf("chill", "focus"),
        attentionMode = "background",
        seedTrackId = null,
        seedGenres = listOf("rock", "pop"),
    )

    private fun queueEntryEntity(
        sessionId: UUID,
        position: Int,
    ) = AdaptiveQueueEntryEntity(
        id = UUID.randomUUID(),
        sessionId = sessionId,
        trackId = UUID.randomUUID(),
        position = position,
        addedReason = "llm",
        intentLabel = "energy_boost",
        status = "QUEUED",
        queueVersion = 1,
        addedAt = now,
        playedAt = null,
    )

    private fun signalEntity(
        sessionId: UUID,
        createdAt: Instant,
    ) = PlaybackSignalEntity(
        id = UUID.randomUUID(),
        sessionId = sessionId,
        trackId = UUID.randomUUID(),
        queueEntryId = null,
        signalType = "SKIP",
        context = "{}",
        createdAt = createdAt,
    )

    private fun decisionEntity(
        sessionId: UUID,
        createdAt: Instant,
    ) = LlmDecisionEntity(
        id = UUID.randomUUID(),
        sessionId = sessionId,
        triggerType = "SIGNAL",
        triggerSignalId = null,
        promptHash = "abc123",
        promptTokens = 100,
        completionTokens = 50,
        modelId = "gpt-4",
        latencyMs = 500,
        intent = "{}",
        edits = "[]",
        baseQueueVersion = 1,
        appliedQueueVersion = 2,
        validationResult = "ACCEPTED",
        validationDetails = "{}",
        createdAt = createdAt,
    )
}
