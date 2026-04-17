package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "playback_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackGroupEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "owner_user_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_playback_group_owner"))
  private UserEntity owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "listening_session_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_playback_group_session"))
  private ListeningSessionEntity listeningSession;

  @Column(name = "canonical_device_id", nullable = false, length = 255)
  private String canonicalDeviceId;

  @Column(length = 100)
  private String name;

  @Column(name = "join_code", nullable = false, unique = true, length = 16)
  private String joinCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "ended_at")
  private OffsetDateTime endedAt;

  @Version private long version;
}
