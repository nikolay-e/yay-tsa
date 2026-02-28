package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "listening_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ListeningSessionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "user_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_listening_session_user"))
  private UserEntity user;

  @Column(columnDefinition = "jsonb", nullable = false)
  private String state = "{}";

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "last_activity_at", nullable = false)
  private OffsetDateTime lastActivityAt;

  @Column(name = "ended_at")
  private OffsetDateTime endedAt;

  @Column(name = "session_summary", columnDefinition = "TEXT")
  private String sessionSummary;
}
