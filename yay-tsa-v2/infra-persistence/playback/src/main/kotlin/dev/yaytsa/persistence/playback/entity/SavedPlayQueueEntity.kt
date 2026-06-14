package dev.yaytsa.persistence.playback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "saved_play_queue", schema = "core_v2_playback")
class SavedPlayQueueEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "track_ids", columnDefinition = "text[]", nullable = false)
    var trackIds: List<String> = emptyList(),
    @Column(name = "current_track_id")
    var currentTrackId: String? = null,
    @Column(name = "position_ms", nullable = false)
    var positionMs: Long = 0,
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now(),
    @Column(name = "changed_by")
    var changedBy: String? = null,
)
