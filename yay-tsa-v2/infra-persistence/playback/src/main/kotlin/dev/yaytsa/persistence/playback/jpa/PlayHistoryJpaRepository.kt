package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PlayHistoryJpaRepository : JpaRepository<PlayHistoryEntity, UUID> {
    @Modifying
    @Query(
        value =
            """
            INSERT INTO core_v2_playback.play_history
                (id, user_id, item_id, started_at, duration_ms, played_ms, completed, scrobbled, skipped, recorded_at)
            SELECT :id, :userId, :itemId, :startedAt, :durationMs, :playedMs, :completed, FALSE, :skipped, :recordedAt
            WHERE NOT EXISTS (
                SELECT 1 FROM core_v2_playback.play_history existing
                WHERE existing.user_id = :userId
                  AND existing.item_id = :itemId
                  AND existing.recorded_at > CAST(:recordedAt AS timestamptz) - make_interval(secs => CAST(:dedupWindowSeconds AS double precision))
            )
            """,
        nativeQuery = true,
    )
    fun insertUnlessRecentDuplicate(
        @Param("id") id: UUID,
        @Param("userId") userId: String,
        @Param("itemId") itemId: String,
        @Param("startedAt") startedAt: Instant,
        @Param("durationMs") durationMs: Long,
        @Param("playedMs") playedMs: Long,
        @Param("completed") completed: Boolean,
        @Param("skipped") skipped: Boolean,
        @Param("recordedAt") recordedAt: Instant,
        @Param("dedupWindowSeconds") dedupWindowSeconds: Long,
    ): Int
}
