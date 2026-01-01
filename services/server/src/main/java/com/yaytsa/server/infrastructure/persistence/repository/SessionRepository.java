package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
  Optional<SessionEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);

  List<SessionEntity> findAllByUserId(UUID userId);

  @EntityGraph(attributePaths = {"user", "nowPlayingItem"})
  @Query("SELECT s FROM SessionEntity s")
  List<SessionEntity> findAllWithUserAndItem();

  @EntityGraph(attributePaths = {"user", "nowPlayingItem"})
  @Query("SELECT s FROM SessionEntity s WHERE s.id = :id")
  Optional<SessionEntity> findByIdWithUserAndItem(UUID id);
}
