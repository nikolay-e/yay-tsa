package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
