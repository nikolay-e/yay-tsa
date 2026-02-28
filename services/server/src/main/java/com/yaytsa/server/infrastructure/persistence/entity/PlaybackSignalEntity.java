package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "playback_signal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackSignalEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "session_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_playback_signal_session"))
  private ListeningSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "track_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_playback_signal_item"))
  private ItemEntity item;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "queue_entry_id",
      foreignKey = @ForeignKey(name = "fk_playback_signal_queue_entry"))
  private AdaptiveQueueEntity queueEntry;

  @Column(name = "signal_type", length = 30, nullable = false)
  private String signalType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String context = "{}";

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;
}
