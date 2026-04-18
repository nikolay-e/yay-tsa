package dev.yaytsa.persistence.playback.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

data class QueueEntryEntityId(
    var userId: String = "",
    var sessionId: String = "",
    var entryId: String = "",
) : Serializable

@Entity
@Table(name = "queue_entries", schema = "core_v2_playback")
@IdClass(QueueEntryEntityId::class)
class QueueEntryEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Id
    @Column(name = "session_id", nullable = false)
    var sessionId: String = "",
    @Id
    @Column(name = "entry_id", nullable = false)
    var entryId: String = "",
    @Column(name = "track_id", nullable = false)
    var trackId: String = "",
    @Column(name = "position", nullable = false)
    var position: Int = 0,
)
