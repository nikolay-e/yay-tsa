package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "play_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlayHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "user_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_play_history_user"))
  private UserEntity user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "item_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_play_history_item"))
  private ItemEntity item;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "duration_ms", nullable = false)
  private Long durationMs;

  @Column(name = "played_ms", nullable = false)
  private Long playedMs = 0L;

  @Column(nullable = false)
  private Boolean completed = false;

  @Column(nullable = false)
  private Boolean scrobbled = false;

  @Column(nullable = false)
  private Boolean skipped = false;
}
