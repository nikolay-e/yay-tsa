package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "playback_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackScheduleEntity {

  @Id
  @Column(name = "group_id")
  private UUID groupId;

  @Column(name = "track_id", nullable = false)
  private UUID trackId;

  @Column(name = "anchor_server_ms", nullable = false)
  private long anchorServerMs;

  @Column(name = "anchor_position_ms", nullable = false)
  private long anchorPositionMs;

  @Column(name = "is_paused", nullable = false)
  private boolean paused;

  @Column(name = "schedule_epoch", nullable = false)
  private long scheduleEpoch;

  @Column(name = "next_track_id")
  private UUID nextTrackId;

  @Column(name = "next_track_anchor_ms")
  private Long nextTrackAnchorMs;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
