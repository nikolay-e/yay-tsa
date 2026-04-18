package dev.yaytsa.persistence.adaptive.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "adaptive_queue", schema = "core_v2_adaptive")
class AdaptiveQueueEntryEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID = UUID.randomUUID(),
    @Column(name = "track_id", nullable = false)
    val trackId: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val position: Int = 0,
    @Column(name = "added_reason")
    val addedReason: String? = null,
    @Column(name = "intent_label")
    val intentLabel: String? = null,
    @Column(nullable = false)
    val status: String = "QUEUED",
    @Column(name = "queue_version", nullable = false)
    val queueVersion: Long = 1,
    @Column(name = "added_at", nullable = false)
    val addedAt: Instant = Instant.now(),
    @Column(name = "played_at")
    val playedAt: Instant? = null,
)
