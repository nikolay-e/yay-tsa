package dev.yaytsa.persistence.ml.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class UserTrackAffinityId(
    var userId: UUID = UUID.randomUUID(),
    var trackId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "user_track_affinity", schema = "core_v2_ml")
@IdClass(UserTrackAffinityId::class)
class UserTrackAffinityEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    val userId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "track_id", nullable = false)
    val trackId: UUID = UUID.randomUUID(),
    @Column(name = "affinity_score", nullable = false)
    val affinityScore: Double = 0.0,
    @Column(name = "play_count", nullable = false)
    val playCount: Int = 0,
    @Column(name = "completion_count", nullable = false)
    val completionCount: Int = 0,
    @Column(name = "skip_count", nullable = false)
    val skipCount: Int = 0,
    @Column(name = "thumbs_up_count", nullable = false)
    val thumbsUpCount: Int = 0,
    @Column(name = "thumbs_down_count", nullable = false)
    val thumbsDownCount: Int = 0,
    @Column(name = "total_listen_sec", nullable = false)
    val totalListenSec: Int = 0,
    @Column(name = "last_signal_at")
    val lastSignalAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
