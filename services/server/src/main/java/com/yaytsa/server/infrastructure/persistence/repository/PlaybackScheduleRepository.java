package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlaybackScheduleEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaybackScheduleRepository extends JpaRepository<PlaybackScheduleEntity, UUID> {

  @Modifying
  @Query(
      value =
          "UPDATE playback_schedule SET track_id = COALESCE(:trackId, track_id),"
              + " anchor_server_ms = :anchorServerMs,"
              + " anchor_position_ms = :anchorPositionMs,"
              + " is_paused = :isPaused,"
              + " schedule_epoch = schedule_epoch + 1,"
              + " next_track_id = :nextTrackId,"
              + " next_track_anchor_ms = :nextTrackAnchorMs,"
              + " updated_at = now()"
              + " WHERE group_id = :groupId AND schedule_epoch = :expectedEpoch",
      nativeQuery = true)
  int updateSchedule(
      @Param("groupId") UUID groupId,
      @Param("expectedEpoch") long expectedEpoch,
      @Param("trackId") UUID trackId,
      @Param("anchorServerMs") long anchorServerMs,
      @Param("anchorPositionMs") long anchorPositionMs,
      @Param("isPaused") boolean isPaused,
      @Param("nextTrackId") UUID nextTrackId,
      @Param("nextTrackAnchorMs") Long nextTrackAnchorMs);
}
