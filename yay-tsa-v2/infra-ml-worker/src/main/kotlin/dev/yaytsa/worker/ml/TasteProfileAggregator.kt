package dev.yaytsa.worker.ml

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Rebuilds `core_v2_ml.taste_profiles` from `user_track_affinity` + library metadata.
 * The original writer lived in the retired v1 backend (taste_profiles stale since
 * 2026-05-15); the LLM-DJ prompt reads `summary_text` and silently lost its taste
 * context when v1 was cut over. This restores the only consumed field. Fast single
 * SQL statement — no dedicated executor needed (unlike KaraokeProcessor's minutes-long
 * separation calls).
 */
@Component
@ConditionalOnProperty(name = ["yaytsa.taste-profile.enabled"], havingValue = "true", matchIfMissing = true)
class TasteProfileAggregator(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${yaytsa.taste-profile.rebuild-interval-ms:21600000}", initialDelay = 120_000)
    @Transactional
    fun rebuild() {
        val rebuilt = jdbc.update(REBUILD_SQL)
        if (rebuilt > 0) log.info("Rebuilt taste profiles for {} users", rebuilt)
    }

    companion object {
        private const val TOP_NAMES_PER_FACET = 5

        private val REBUILD_SQL = """
            WITH artist_scores AS (
                SELECT uta.user_id, e.name,
                       ROW_NUMBER() OVER (
                           PARTITION BY uta.user_id
                           ORDER BY SUM(uta.affinity_score) DESC, e.name
                       ) AS rank
                FROM core_v2_ml.user_track_affinity uta
                JOIN core_v2_library.audio_tracks tr ON tr.entity_id = uta.track_id
                JOIN core_v2_library.entities e ON e.id = tr.album_artist_id
                WHERE uta.affinity_score > 0
                GROUP BY uta.user_id, e.name
            ),
            genre_scores AS (
                SELECT uta.user_id, g.name,
                       ROW_NUMBER() OVER (
                           PARTITION BY uta.user_id
                           ORDER BY SUM(uta.affinity_score) DESC, g.name
                       ) AS rank
                FROM core_v2_ml.user_track_affinity uta
                JOIN core_v2_library.entity_genres eg ON eg.entity_id = uta.track_id
                JOIN core_v2_library.genres g ON g.id = eg.genre_id
                WHERE uta.affinity_score > 0
                GROUP BY uta.user_id, g.name
            ),
            per_user AS (
                SELECT user_id, count(*) AS liked_tracks
                FROM core_v2_ml.user_track_affinity
                WHERE affinity_score > 0
                GROUP BY user_id
            )
            INSERT INTO core_v2_ml.taste_profiles (user_id, profile, summary_text, rebuilt_at, track_count)
            SELECT
                pu.user_id,
                'affinity-v1',
                concat_ws('. ',
                    'Top artists: ' || (
                        SELECT string_agg(name, ', ' ORDER BY rank)
                        FROM artist_scores a WHERE a.user_id = pu.user_id AND a.rank <= $TOP_NAMES_PER_FACET
                    ),
                    'Top genres: ' || (
                        SELECT string_agg(name, ', ' ORDER BY rank)
                        FROM genre_scores g WHERE g.user_id = pu.user_id AND g.rank <= $TOP_NAMES_PER_FACET
                    )
                ),
                now(),
                pu.liked_tracks
            FROM per_user pu
            ON CONFLICT (user_id) DO UPDATE SET
                profile      = EXCLUDED.profile,
                summary_text = EXCLUDED.summary_text,
                rebuilt_at   = EXCLUDED.rebuilt_at,
                track_count  = EXCLUDED.track_count
        """
    }
}
