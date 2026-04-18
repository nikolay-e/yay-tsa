package dev.yaytsa.persistence.playback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class PlaybackSessionEntityId(
    var userId: String = "",
    var sessionId: String = "",
) : Serializable

@Entity
@Table(name = "playback_sessions", schema = "core_v2_playback")
@IdClass(PlaybackSessionEntityId::class)
class PlaybackSessionEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Id
    @Column(name = "session_id", nullable = false)
    var sessionId: String = "",
    @Column(name = "current_entry_id")
    var currentEntryId: String? = null,
    @Column(name = "playback_state", nullable = false)
    var playbackState: String = "STOPPED",
    @Column(name = "last_known_position_ms", nullable = false)
    var lastKnownPositionMs: Long = 0,
    @Column(name = "last_known_at", nullable = false)
    var lastKnownAt: Instant = Instant.now(),
    @Column(name = "lease_owner")
    var leaseOwner: String? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
