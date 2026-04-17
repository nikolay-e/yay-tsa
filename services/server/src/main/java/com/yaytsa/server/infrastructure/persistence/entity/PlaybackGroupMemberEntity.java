package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "playback_group_member")
@IdClass(PlaybackGroupMemberEntity.MemberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackGroupMemberEntity {

  @EqualsAndHashCode
  @NoArgsConstructor
  @AllArgsConstructor
  public static class MemberId implements Serializable {
    private UUID groupId;
    private String deviceId;
  }

  @Id
  @Column(name = "group_id", nullable = false)
  private UUID groupId;

  @Id
  @Column(name = "device_id", nullable = false, length = 255)
  private String deviceId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "joined_at", nullable = false)
  private OffsetDateTime joinedAt;

  @Column(name = "last_heartbeat_at", nullable = false)
  private OffsetDateTime lastHeartbeatAt;

  @Column(nullable = false)
  private boolean stale;

  @Column(name = "reported_rtt_ms")
  private Integer reportedRttMs;

  @Column(name = "reported_latency_ms", nullable = false)
  private int reportedLatencyMs;
}
