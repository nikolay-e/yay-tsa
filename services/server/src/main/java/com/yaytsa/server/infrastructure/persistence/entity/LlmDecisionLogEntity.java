package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "llm_decision_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LlmDecisionLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "session_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_llm_decision_log_session"))
  private ListeningSessionEntity session;

  @Column(name = "trigger_type", length = 30, nullable = false)
  private String triggerType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "trigger_signal_id",
      foreignKey = @ForeignKey(name = "fk_llm_decision_log_signal"))
  private PlaybackSignalEntity triggerSignal;

  @Column(name = "prompt_hash", length = 64)
  private String promptHash;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "model_id", length = 50)
  private String modelId;

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @Column(columnDefinition = "jsonb")
  private String intent;

  @Column(columnDefinition = "jsonb")
  private String edits;

  @Column(name = "base_queue_version")
  private Long baseQueueVersion;

  @Column(name = "applied_queue_version")
  private Long appliedQueueVersion;

  @Column(name = "validation_result", length = 20)
  private String validationResult;

  @Column(name = "validation_details", columnDefinition = "jsonb")
  private String validationDetails;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;
}
