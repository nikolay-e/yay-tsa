package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  @EntityGraph(attributePaths = {"nowPlayingItem"})
  @Query("SELECT s FROM SessionEntity s WHERE s.user.id = :userId ORDER BY s.lastUpdate DESC")
  List<SessionEntity> findAllByUserIdWithItem(@Param("userId") UUID userId);

  @Modifying
  @Query(
      value =
          "UPDATE sessions SET is_online = false"
              + " WHERE is_online = true AND last_heartbeat_at < :cutoff",
      nativeQuery = true)
  int markOffline(@Param("cutoff") OffsetDateTime cutoff);

  @EntityGraph(attributePaths = {"user"})
  @Query("SELECT s FROM SessionEntity s WHERE s.online = true" + " AND s.lastHeartbeatAt < :cutoff")
  List<SessionEntity> findOnlineBeforeCutoff(@Param("cutoff") OffsetDateTime cutoff);

  @Modifying
  @Query(value = "DELETE FROM sessions WHERE last_update < :cutoff", nativeQuery = true)
  int deleteByLastUpdateBefore(@Param("cutoff") OffsetDateTime cutoff);
}
