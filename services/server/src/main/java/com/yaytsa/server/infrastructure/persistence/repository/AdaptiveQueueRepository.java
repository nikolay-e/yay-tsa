package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AdaptiveQueueRepository extends JpaRepository<AdaptiveQueueEntity, UUID> {
  List<AdaptiveQueueEntity> findBySessionIdAndStatusInOrderByPositionAsc(
      UUID sessionId, Collection<String> statuses);

  @Query(
      value = "SELECT MAX(queue_version) FROM adaptive_queue WHERE session_id = :sessionId",
      nativeQuery = true)
  Optional<Long> findMaxQueueVersionBySessionId(@Param("sessionId") UUID sessionId);

  long countBySessionIdAndStatusIn(UUID sessionId, Collection<String> statuses);

  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE adaptive_queue
          SET status = 'SKIPPED', played_at = :playedAt
          WHERE session_id = :sessionId
            AND status IN ('QUEUED', 'PLAYING')
            AND position < (
              SELECT MIN(q2.position) FROM adaptive_queue q2
              WHERE q2.session_id = :sessionId
                AND q2.track_id = :trackId
                AND q2.status IN ('QUEUED', 'PLAYING')
            )
          """,
      nativeQuery = true)
  int skipEntriesBeforeTrack(
      @Param("sessionId") UUID sessionId,
      @Param("trackId") UUID trackId,
      @Param("playedAt") OffsetDateTime playedAt);

  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE adaptive_queue
          SET status = :status, played_at = :playedAt
          WHERE session_id = :sessionId
            AND track_id = :trackId
            AND status IN ('QUEUED', 'PLAYING')
            AND position = (
              SELECT MIN(q2.position) FROM adaptive_queue q2
              WHERE q2.session_id = :sessionId
                AND q2.track_id = :trackId
                AND q2.status IN ('QUEUED', 'PLAYING')
            )
          """,
      nativeQuery = true)
  int markTrackConsumed(
      @Param("sessionId") UUID sessionId,
      @Param("trackId") UUID trackId,
      @Param("status") String status,
      @Param("playedAt") OffsetDateTime playedAt);

  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE adaptive_queue SET position = position + :shiftBy
          WHERE session_id = :sessionId AND status = 'QUEUED' AND position > :afterPosition
          """,
      nativeQuery = true)
  int shiftPositionsAfter(
      @Param("sessionId") UUID sessionId,
      @Param("afterPosition") int afterPosition,
      @Param("shiftBy") int shiftBy);
}
