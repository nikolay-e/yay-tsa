package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "adaptive_queue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveQueueEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "session_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_adaptive_queue_session"))
  private ListeningSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "track_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_adaptive_queue_item"))
  private ItemEntity item;

  @Column(nullable = false)
  private int position;

  @Column(name = "added_reason", columnDefinition = "TEXT")
  private String addedReason;

  @Column(name = "intent_label", length = 100)
  private String intentLabel;

  @Column(length = 20, nullable = false)
  private String status = "QUEUED";

  @Column(name = "queue_version", nullable = false)
  private long queueVersion = 1;

  @Version
  @Column(name = "entity_version")
  private long entityVersion;

  @Column(name = "added_at", nullable = false)
  private OffsetDateTime addedAt;

  @Column(name = "played_at")
  private OffsetDateTime playedAt;
}
