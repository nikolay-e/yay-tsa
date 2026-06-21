package dev.yaytsa.worker.ml

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/**
 * Worker that aggregates rows from `core_v2_adaptive.playback_signals` into
 * `core_v2_ml.user_track_affinity`. Without this, user actions (skips, completions, thumbs)
 * are recorded but never influence ML-driven recommendations.
 *
 * The watermark is the `created_at` of the last signal consumed, persisted in
 * `core_v2_ml.affinity_cursor`. Both the watermark and the `WHERE ps.created_at > :watermark`
 * filter are therefore driven by the same clock (signal emit-time), so a signal can never be
 * skipped by reading the watermark from a different clock than the one the filter compares
 * against. The affinity UPSERT folds additive deltas, so the watermark must be advanced
 * strictly to the max consumed `created_at` and the filter must stay strict (`>`): equal
 * timestamps already counted are never re-scanned (no double-count), and anything newer than
 * the last consumed signal is always picked up (no drop). Score weights are intentionally
 * conservative so a single accidental skip doesn't tank a track's affinity.
 */
@Component
@ConditionalOnProperty(name = ["yaytsa.affinity.enabled"], havingValue = "true", matchIfMissing = true)
class AffinityAggregator(
    private val jdbc: JdbcTemplate,
    @Value("\${yaytsa.affinity.poll-interval-ms:60000}") private val pollIntervalMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${yaytsa.affinity.poll-interval-ms:60000}", initialDelay = 30_000)
    @Transactional
    fun aggregate() {
        val watermark =
            jdbc.queryForObject(WATERMARK_SQL, Timestamp::class.java)?.toInstant() ?: Instant.EPOCH
        val watermarkTs = Timestamp.from(watermark)

        val foldedPairs =
            jdbc
                .query(UPSERT_SQL, { rs, _ -> rs.getInt(1) }, watermarkTs)
                .firstOrNull() ?: 0

        if (foldedPairs > 0) {
            log.info(
                "Aggregated affinity deltas for {} (user, track) pairs after watermark {} (poll {}ms)",
                foldedPairs,
                watermark,
                pollIntervalMs,
            )
        }
    }

    companion object {
        private const val WATERMARK_SQL =
            "SELECT last_signal_at FROM core_v2_ml.affinity_cursor WHERE id = TRUE"

        // Score weights:
        //   PLAY_COMPLETE        +1.0  — listened to the end
        //   THUMBS_UP            +2.0  — explicit positive signal
        //   FAVORITE_TOGGLE      +1.5  — added to favorites (we don't track toggle direction yet)
        //   REPEAT_TRACK         +0.5  — manually replayed
        //   SKIP_EARLY           -0.5  — skipped within first ~25%
        //   SKIP_MID             -0.2  — skipped mid-track
        //   THUMBS_DOWN          -2.0  — explicit negative signal
        //   SKIP_LATE / PLAY_START / SEEK_*: 0  — neutral
        // A single statement so the affinity fold and the cursor advance share one snapshot:
        // the writable CTE `folded` consumes exactly the rows in `consumed`, and the cursor is
        // advanced to MAX(consumed.created_at) over that same scan. No row can be folded but
        // left behind the cursor (double-count), nor advanced past without folding (drop).
        private const val UPSERT_SQL = """
            WITH consumed AS (
                SELECT ls.user_id, ps.track_id, ps.signal_type, ps.context, ps.created_at
                FROM core_v2_adaptive.playback_signals ps
                JOIN core_v2_adaptive.listening_sessions ls ON ls.id = ps.session_id
                WHERE ps.created_at > ?
            ),
            folded AS (
                INSERT INTO core_v2_ml.user_track_affinity AS uta (
                    user_id, track_id, affinity_score,
                    play_count, completion_count, skip_count, thumbs_up_count, thumbs_down_count,
                    total_listen_sec, last_signal_at, updated_at
                )
                SELECT
                    c.user_id,
                    c.track_id,
                    COALESCE(SUM(CASE
                        WHEN c.signal_type = 'PLAY_COMPLETE'  THEN  1.0
                        WHEN c.signal_type = 'THUMBS_UP'      THEN  2.0
                        WHEN c.signal_type = 'FAVORITE_TOGGLE' THEN  1.5
                        WHEN c.signal_type = 'REPEAT_TRACK'   THEN  0.5
                        WHEN c.signal_type = 'SKIP_EARLY'     THEN -0.5
                        WHEN c.signal_type = 'SKIP_MID'       THEN -0.2
                        WHEN c.signal_type = 'THUMBS_DOWN'    THEN -2.0
                        ELSE 0
                    END), 0),
                    SUM(CASE WHEN c.signal_type = 'PLAY_START'    THEN 1 ELSE 0 END),
                    SUM(CASE WHEN c.signal_type = 'PLAY_COMPLETE' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN c.signal_type IN ('SKIP_EARLY','SKIP_MID','SKIP_LATE') THEN 1 ELSE 0 END),
                    SUM(CASE WHEN c.signal_type = 'THUMBS_UP'   THEN 1 ELSE 0 END),
                    SUM(CASE WHEN c.signal_type = 'THUMBS_DOWN' THEN 1 ELSE 0 END),
                    COALESCE(SUM(
                        CASE
                            WHEN c.signal_type IN ('PLAY_COMPLETE','SKIP_LATE','SKIP_MID','SKIP_EARLY')
                            THEN COALESCE(
                                     substring(c.context::text from '"positionMs"[[:space:]]*:[[:space:]]*([0-9]+)')::bigint,
                                     0
                                 ) / 1000
                            ELSE 0
                        END
                    ), 0),
                    MAX(c.created_at),
                    now()
                FROM consumed c
                GROUP BY c.user_id, c.track_id
                ON CONFLICT (user_id, track_id) DO UPDATE SET
                    affinity_score     = uta.affinity_score     + EXCLUDED.affinity_score,
                    play_count         = uta.play_count         + EXCLUDED.play_count,
                    completion_count   = uta.completion_count   + EXCLUDED.completion_count,
                    skip_count         = uta.skip_count         + EXCLUDED.skip_count,
                    thumbs_up_count    = uta.thumbs_up_count    + EXCLUDED.thumbs_up_count,
                    thumbs_down_count  = uta.thumbs_down_count  + EXCLUDED.thumbs_down_count,
                    total_listen_sec   = uta.total_listen_sec   + EXCLUDED.total_listen_sec,
                    last_signal_at     = GREATEST(uta.last_signal_at, EXCLUDED.last_signal_at),
                    updated_at         = now()
                RETURNING 1
            )
            UPDATE core_v2_ml.affinity_cursor
            SET last_signal_at = GREATEST(last_signal_at, (SELECT MAX(created_at) FROM consumed)),
                updated_at = now()
            WHERE id = TRUE
              AND EXISTS (SELECT 1 FROM consumed)
            RETURNING (SELECT count(*) FROM folded)::int
        """
    }
}
