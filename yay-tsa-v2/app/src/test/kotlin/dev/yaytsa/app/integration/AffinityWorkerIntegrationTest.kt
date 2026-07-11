package dev.yaytsa.app.integration

import dev.yaytsa.worker.ml.AffinityAggregator
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

    @BeforeEach
    fun resetWatermark() {
        jdbc.update("UPDATE core_v2_ml.affinity_cursor SET last_signal_at = 'epoch', last_history_at = 'epoch' WHERE id = TRUE")
    }

    private fun seedHistory(
        userId: UUID,
        trackId: UUID,
        completed: Boolean,
        skipped: Boolean,
        source: String?,
        playedMs: Long = 120_000,
        at: Instant = Instant.now(),
    ) {
        jdbc.update(
            "INSERT INTO core_v2_playback.play_history " +
                "(id, user_id, item_id, started_at, duration_ms, played_ms, completed, skipped, source) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            userId.toString(),
            trackId.toString(),
            Timestamp.from(at),
            240_000L,
            playedMs,
            completed,
            skipped,
            source,
        )
    }

    private fun playCount(
        userId: UUID,
        trackId: UUID,
    ): Int? =
        jdbc
            .query(
                "SELECT play_count FROM core_v2_ml.user_track_affinity WHERE user_id=? AND track_id=?",
                { rs, _ -> rs.getInt("play_count") },
                userId,
                trackId,
            ).firstOrNull()

    @Test
    fun `aggregator folds non-adaptive play_history into affinity (fixes non-DJ listening blindness)`() {
        val userId = UUID.randomUUID()
        val finishedTrack = UUID.randomUUID()
        val bailedTrack = UUID.randomUUID()
        // Plain listening: no adaptive session, no signals — only play_history rows.
        seedHistory(userId, finishedTrack, completed = true, skipped = false, source = null)
        seedHistory(userId, bailedTrack, completed = false, skipped = true, source = "subsonic")

        aggregator.aggregate()

        val finished = affinityScore(userId, finishedTrack)
        val bailed = affinityScore(userId, bailedTrack)
        assertNotNull(finished, "completed non-adaptive play must produce an affinity row — history fold is dead")
        assertNotNull(bailed, "skipped non-adaptive play must produce an affinity row")
        assertTrue(finished!! > 0, "completed listen must raise affinity, was $finished")
        assertTrue(bailed!! < 0, "skipped self-picked track must lower affinity, was $bailed")
        assertTrue((playCount(userId, finishedTrack) ?: 0) >= 1, "play_count must reflect the history play")
    }

    @Test
    fun `history fold survives fuzz-polluted play_history rows (empty non-uuid ids)`() {
        val userId = UUID.randomUUID()
        val goodTrack = UUID.randomUUID()
        // A fuzz/garbage row with a non-UUID item_id must not abort the whole fold (SQLState 22P02).
        jdbc.update(
            "INSERT INTO core_v2_playback.play_history " +
                "(id, user_id, item_id, started_at, duration_ms, played_ms, completed, skipped) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            userId.toString(),
            "",
            Timestamp.from(Instant.now()),
            240_000L,
            120_000L,
            true,
            false,
        )
        seedHistory(userId, goodTrack, completed = true, skipped = false, source = null)

        aggregator.aggregate()

        assertNotNull(affinityScore(userId, goodTrack), "valid rows must still fold despite a garbage row in the batch")
    }

    @Test
    fun `adaptive-source play_history is skipped by the history fold (no double count with signals)`() {
        val userId = UUID.randomUUID()
        val track = UUID.randomUUID()
        // An adaptive play is already owned by the signals fold; the history fold must ignore it.
        seedHistory(userId, track, completed = true, skipped = false, source = "adaptive")

        aggregator.aggregate()

        assertNull(affinityScore(userId, track), "source='adaptive' history rows must not be folded twice")
    }

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
