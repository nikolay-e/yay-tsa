package dev.yaytsa.persistence.playback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "play_history", schema = "core_v2_playback")
class PlayHistoryEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: String = "",
    @Column(name = "item_id", nullable = false)
    val itemId: String = "",
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),
    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long = 0,
    @Column(name = "played_ms", nullable = false)
    val playedMs: Long = 0,
    @Column(nullable = false)
    val completed: Boolean = false,
    @Column(nullable = false)
    val scrobbled: Boolean = false,
    @Column(nullable = false)
    val skipped: Boolean = false,
)
