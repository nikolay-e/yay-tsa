package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ListeningSessionRepository extends JpaRepository<ListeningSessionEntity, UUID> {
  Optional<ListeningSessionEntity> findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(
      UUID userId);

  @Query(
      "SELECT s FROM ListeningSessionEntity s WHERE s.endedAt IS NULL"
          + " AND s.lastActivityAt < :cutoff")
  List<ListeningSessionEntity> findStaleSessions(@Param("cutoff") OffsetDateTime cutoff);

  @Modifying
  @Query(
      value = "DELETE FROM listening_session WHERE ended_at IS NOT NULL AND ended_at < :cutoff",
      nativeQuery = true)
  int deleteEndedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
