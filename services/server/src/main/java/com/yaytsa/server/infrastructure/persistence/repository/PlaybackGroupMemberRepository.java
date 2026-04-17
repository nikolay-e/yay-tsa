package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlaybackGroupMemberEntity;
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
public interface PlaybackGroupMemberRepository
    extends JpaRepository<PlaybackGroupMemberEntity, PlaybackGroupMemberEntity.MemberId> {

  List<PlaybackGroupMemberEntity> findByGroupId(UUID groupId);

  Optional<PlaybackGroupMemberEntity> findByGroupIdAndDeviceId(UUID groupId, String deviceId);

  @Query(
      "SELECT m FROM PlaybackGroupMemberEntity m"
          + " WHERE m.groupId = :groupId AND m.stale = false")
  List<PlaybackGroupMemberEntity> findActiveMembers(@Param("groupId") UUID groupId);

  @Modifying
  @Query(
      value =
          "UPDATE playback_group_member SET stale = true"
              + " WHERE last_heartbeat_at < :cutoff AND stale = false"
              + " AND group_id IN (SELECT id FROM playback_group WHERE ended_at IS NULL)",
      nativeQuery = true)
  int markAllStaleMembers(@Param("cutoff") OffsetDateTime cutoff);

  void deleteByGroupIdAndDeviceId(UUID groupId, String deviceId);

  long countByGroupIdAndStaleFalse(UUID groupId);
}
