package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface PlayHistoryJpaRepository : JpaRepository<PlayHistoryEntity, UUID> {
    fun findByUserIdAndCompletedTrueAndScrobbledFalseAndRecordedAtAfterOrderByRecordedAtAsc(
        userId: String,
        cutoff: Instant,
        pageable: Pageable,
    ): List<PlayHistoryEntity>

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PlayHistoryEntity p SET p.scrobbled = true WHERE p.id IN :ids")
    fun markScrobbled(
        @Param("ids") ids: Collection<UUID>,
    ): Int

    @Modifying
    @Query(
        value =
            """
            INSERT INTO core_v2_playback.play_history
                (id, user_id, item_id, started_at, duration_ms, played_ms, completed, scrobbled, skipped, recorded_at, source, device_id)
            SELECT :id, :userId, :itemId, :startedAt, :durationMs, :playedMs, :completed, FALSE, :skipped, :recordedAt, :source, :deviceId
            WHERE NOT EXISTS (
                SELECT 1 FROM core_v2_playback.play_history existing
                WHERE existing.user_id = :userId
                  AND existing.item_id = :itemId
                  AND existing.recorded_at > CAST(:recordedAt AS timestamptz) - make_interval(secs => CAST(:dedupWindowSeconds AS double precision))
            )
            """,
        nativeQuery = true,
    )
    @Suppress("LongParameterList")
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
        @Param("source") source: String?,
        @Param("deviceId") deviceId: String?,
    ): Int

    @Query(
        value = """
            SELECT * FROM core_v2_playback.play_history
            WHERE user_id = :userId AND started_at >= :since AND started_at < :until
              AND item_id ~ :uuidPattern
            ORDER BY started_at
        """,
        nativeQuery = true,
    )
    fun findEventsInWindow(
        @Param("userId") userId: String,
        @Param("since") since: Instant,
        @Param("until") until: Instant,
        @Param("uuidPattern") uuidPattern: String,
    ): List<PlayHistoryEntity>

    @Query(
        value =
            """
            SELECT * FROM core_v2_playback.play_history
            WHERE user_id = :userId
              AND (CAST(:since AS timestamptz) IS NULL OR started_at >= CAST(:since AS timestamptz))
              AND (CAST(:until AS timestamptz) IS NULL OR started_at < CAST(:until AS timestamptz))
              AND (CAST(:source AS text) IS NULL OR source = CAST(:source AS text))
              AND item_id ~ :uuidPattern
            ORDER BY started_at DESC, id
            LIMIT :limit OFFSET :offset
            """,
        nativeQuery = true,
    )
    fun findHistoryPage(
        @Param("userId") userId: String,
        @Param("since") since: Instant?,
        @Param("until") until: Instant?,
        @Param("source") source: String?,
        @Param("uuidPattern") uuidPattern: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<PlayHistoryEntity>

    @Query(
        value =
            """
            SELECT count(*) FROM core_v2_playback.play_history
            WHERE user_id = :userId
              AND (CAST(:since AS timestamptz) IS NULL OR started_at >= CAST(:since AS timestamptz))
              AND (CAST(:until AS timestamptz) IS NULL OR started_at < CAST(:until AS timestamptz))
              AND (CAST(:source AS text) IS NULL OR source = CAST(:source AS text))
              AND item_id ~ :uuidPattern
            """,
        nativeQuery = true,
    )
    fun countHistory(
        @Param("userId") userId: String,
        @Param("since") since: Instant?,
        @Param("until") until: Instant?,
        @Param("source") source: String?,
        @Param("uuidPattern") uuidPattern: String,
    ): Long

    @Query(
        value =
            """
            SELECT item_id AS "itemId", count(*) AS "playCount"
            FROM core_v2_playback.play_history
            WHERE user_id = :userId
            GROUP BY item_id
            ORDER BY count(*) DESC, item_id
            LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findMostPlayedItemCountsByUser(
        @Param("userId") userId: String,
        @Param("limit") limit: Int,
    ): List<ItemPlayCount>

    @Query(
        value =
            """
            SELECT item_id
            FROM core_v2_playback.play_history
            WHERE user_id = :userId
            GROUP BY item_id
            ORDER BY max(recorded_at) DESC, item_id
            LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findRecentlyPlayedItemIdsByUser(
        @Param("userId") userId: String,
        @Param("limit") limit: Int,
    ): List<String>

    @Query(
        value =
            """
            SELECT item_id AS "itemId", count(*) AS "playCount"
            FROM core_v2_playback.play_history
            WHERE item_id IN (:itemIds)
            GROUP BY item_id
            """,
        nativeQuery = true,
    )
    fun countPlaysByItemIds(
        @Param("itemIds") itemIds: Collection<String>,
    ): List<ItemPlayCount>
}

interface ItemPlayCount {
    fun getItemId(): String

    fun getPlayCount(): Long
}
