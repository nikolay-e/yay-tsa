package dev.yaytsa.persistence.playback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class ResumePositionEntityId(
    var userId: String = "",
    var itemId: String = "",
) : Serializable

@Entity
@Table(name = "resume_position", schema = "core_v2_playback")
@IdClass(ResumePositionEntityId::class)
class ResumePositionEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Id
    @Column(name = "item_id", nullable = false)
    var itemId: String = "",
    @Column(name = "position_ms", nullable = false)
    var positionMs: Long = 0,
    @Column(name = "run_time_ms", nullable = false)
    var runTimeMs: Long = 0,
    @Column(name = "status", nullable = false)
    var status: String = "in_progress",
    @Column(name = "source_event", nullable = false)
    var sourceEvent: String = "progress",
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
