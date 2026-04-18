package dev.yaytsa.persistence.adaptive.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "playback_signals", schema = "core_v2_adaptive")
class PlaybackSignalEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "session_id", nullable = false)
    val sessionId: UUID = UUID.randomUUID(),
    @Column(name = "track_id", nullable = false)
    val trackId: UUID = UUID.randomUUID(),
    @Column(name = "queue_entry_id")
    val queueEntryId: UUID? = null,
    @Column(name = "signal_type", nullable = false)
    val signalType: String = "",
    @Column(columnDefinition = "TEXT")
    val context: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
