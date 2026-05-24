package dev.yaytsa.app.integration

import dev.yaytsa.worker.ml.AffinityAggregator
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class AffinityWorkerIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var aggregator: AffinityAggregator

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun seedSession(userId: UUID): UUID {
        val sessionId = UUID.randomUUID()
        val ts = Timestamp.from(Instant.now())
        jdbc.update(
            "INSERT INTO core_v2_adaptive.listening_sessions " +
                "(id, user_id, state, started_at, last_activity_at, attention_mode) VALUES (?,?,?,?,?,?)",
            sessionId,
            userId,
            "ACTIVE",
            ts,
            ts,
            "focus",
        )
        return sessionId
    }

    private fun seedSignal(
        sessionId: UUID,
        trackId: UUID,
        signalType: String,
        at: Instant = Instant.now(),
    ) {
        jdbc.update(
            "INSERT INTO core_v2_adaptive.playback_signals " +
                "(id, session_id, track_id, signal_type, created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(),
            sessionId,
            trackId,
            signalType,
            Timestamp.from(at),
        )
    }

    private fun affinityScore(
        userId: UUID,
        trackId: UUID,
    ): Double? =
        jdbc
            .query(
                "SELECT affinity_score FROM core_v2_ml.user_track_affinity WHERE user_id=? AND track_id=?",
                { rs, _ -> rs.getDouble("affinity_score") },
                userId,
                trackId,
            ).firstOrNull()

    @Test
    fun `aggregator raises affinity on PLAY_COMPLETE and lowers it on SKIP_EARLY`() {
        val userId = UUID.randomUUID()
        val likedTrack = UUID.randomUUID()
        val skippedTrack = UUID.randomUUID()
        val sessionId = seedSession(userId)
        seedSignal(sessionId, likedTrack, "PLAY_COMPLETE")
        seedSignal(sessionId, skippedTrack, "SKIP_EARLY")

        aggregator.aggregate()

        val liked = affinityScore(userId, likedTrack)
        val skipped = affinityScore(userId, skippedTrack)
        assertNotNull(liked, "PLAY_COMPLETE produced no affinity row — signal->affinity worker is dead")
        assertNotNull(skipped, "SKIP_EARLY produced no affinity row")
        assertTrue(liked!! > 0, "PLAY_COMPLETE must raise affinity, was $liked")
        assertTrue(skipped!! < 0, "SKIP_EARLY must lower affinity, was $skipped")
    }

    @Test
    fun `aggregator accumulates across ticks and respects the watermark`() {
        val userId = UUID.randomUUID()
        val track = UUID.randomUUID()
        val sessionId = seedSession(userId)
        val base = Instant.now()

        seedSignal(sessionId, track, "PLAY_COMPLETE", base)
        aggregator.aggregate()
        val afterFirst = affinityScore(userId, track)

        // A new signal strictly after the first tick's watermark must be added, not clobbered.
        seedSignal(sessionId, track, "PLAY_COMPLETE", base.plusSeconds(2))
        aggregator.aggregate()
        val afterSecond = affinityScore(userId, track)

        assertNotNull(afterFirst)
        assertNotNull(afterSecond)
        assertTrue(afterSecond!! > afterFirst!!, "second tick must accumulate ($afterSecond should exceed $afterFirst)")
    }
}
