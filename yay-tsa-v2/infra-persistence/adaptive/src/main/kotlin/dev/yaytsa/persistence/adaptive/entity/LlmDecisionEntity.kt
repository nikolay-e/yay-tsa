package dev.yaytsa.persistence.adaptive.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "llm_decisions", schema = "core_v2_adaptive")
class LlmDecisionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID = UUID.randomUUID(),
    @Column(name = "trigger_type", nullable = false)
    val triggerType: String = "",
    @Column(name = "trigger_signal_id")
    val triggerSignalId: UUID? = null,
    @Column(name = "prompt_hash")
    val promptHash: String? = null,
    @Column(name = "prompt_tokens")
    val promptTokens: Int? = null,
    @Column(name = "completion_tokens")
    val completionTokens: Int? = null,
    @Column(name = "model_id")
    val modelId: String? = null,
    @Column(name = "latency_ms")
    val latencyMs: Int? = null,
    @Column(columnDefinition = "TEXT")
    val intent: String? = null,
    @Column(columnDefinition = "TEXT")
    val edits: String? = null,
    @Column(name = "base_queue_version")
    val baseQueueVersion: Long? = null,
    @Column(name = "applied_queue_version")
    val appliedQueueVersion: Long? = null,
    @Column(name = "validation_result")
    val validationResult: String? = null,
    @Column(name = "validation_details", columnDefinition = "TEXT")
    val validationDetails: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
