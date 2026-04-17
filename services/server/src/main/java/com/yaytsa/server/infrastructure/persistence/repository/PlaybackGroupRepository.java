package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlaybackGroupEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaybackGroupRepository extends JpaRepository<PlaybackGroupEntity, UUID> {

  @Query("SELECT g FROM PlaybackGroupEntity g WHERE g.joinCode = :code AND g.endedAt IS NULL")
  Optional<PlaybackGroupEntity> findActiveByJoinCode(@Param("code") String joinCode);

  @Query("SELECT g FROM PlaybackGroupEntity g WHERE g.owner.id = :userId AND g.endedAt IS NULL")
  Optional<PlaybackGroupEntity> findActiveByOwnerId(@Param("userId") UUID userId);

  @Query(
      "SELECT g FROM PlaybackGroupEntity g WHERE g.listeningSession.id = :sessionId"
          + " AND g.endedAt IS NULL")
  Optional<PlaybackGroupEntity> findActiveBySessionId(@Param("sessionId") UUID sessionId);
}
