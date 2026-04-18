package dev.yaytsa.persistence.adaptive.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "listening_sessions", schema = "core_v2_adaptive")
class ListeningSessionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID = UUID.randomUUID(),
    @Column(name = "state", nullable = false, length = 20)
    val state: String = "ACTIVE",
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),
    @Column(name = "last_activity_at", nullable = false)
    val lastActivityAt: Instant = Instant.now(),
    @Column(name = "ended_at")
    val endedAt: Instant? = null,
    @Column(name = "session_summary")
    val sessionSummary: String? = null,
    val energy: Float? = null,
    val intensity: Float? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mood_tags", columnDefinition = "text[]")
    val moodTags: List<String> = emptyList(),
    @Column(name = "attention_mode", nullable = false)
    val attentionMode: String = "",
    @Column(name = "seed_track_id")
    val seedTrackId: UUID? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "seed_genres", columnDefinition = "text[]")
    val seedGenres: List<String> = emptyList(),
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
