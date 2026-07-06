package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.ResumePositionEntity
import dev.yaytsa.persistence.playback.entity.ResumePositionEntityId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface ResumePositionJpaRepository : JpaRepository<ResumePositionEntity, ResumePositionEntityId> {
    fun findByUserId(userId: String): List<ResumePositionEntity>

    fun findByUserIdAndItemIdIn(
        userId: String,
        itemIds: Collection<String>,
    ): List<ResumePositionEntity>

    // Idempotent write keyed on the (user_id, item_id) primary key. Relying on jpa.save() ran a
    // read-then-INSERT/UPDATE (merge), so two concurrent FIRST progress reports for the same track
    // both saw an empty row and both INSERTed -> duplicate key violation on resume_position_pkey
    // (logged at ERROR by Hibernate; the recorded position was lost). ON CONFLICT DO UPDATE makes
    // the write race-free and last-write-wins. clearAutomatically evicts the now-stale managed
    // entity so a subsequent find() in the same persistence context re-reads the upserted row.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO core_v2_playback.resume_position
                (user_id, item_id, position_ms, run_time_ms, status, source_event, updated_at)
            VALUES (:userId, :itemId, :positionMs, :runTimeMs, :status, :sourceEvent, :updatedAt)
            ON CONFLICT (user_id, item_id) DO UPDATE SET
                position_ms = EXCLUDED.position_ms,
                run_time_ms = EXCLUDED.run_time_ms,
                status = EXCLUDED.status,
                source_event = EXCLUDED.source_event,
                updated_at = EXCLUDED.updated_at
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("userId") userId: String,
        @Param("itemId") itemId: String,
        @Param("positionMs") positionMs: Long,
        @Param("runTimeMs") runTimeMs: Long,
        @Param("status") status: String,
        @Param("sourceEvent") sourceEvent: String,
        @Param("updatedAt") updatedAt: Instant,
    )
}
