package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

enum class SessionState { ACTIVE, ENDED }

data class AdaptiveSessionAggregate(
    val id: ListeningSessionId,
    val userId: UserId,
    val state: SessionState,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val endedAt: Instant?,
    val sessionSummary: String?,
    val energy: Float?,
    val intensity: Float?,
    val moodTags: List<String>,
    val attentionMode: String,
    val seedTrackId: EntityId?,
    val seedGenres: List<String>,
    val queue: List<AdaptiveQueueEntryData>,
    val queueVersion: Long,
    val version: AggregateVersion,
) {
    companion object {
        fun start(
            id: ListeningSessionId,
            userId: UserId,
            attentionMode: String,
            seedTrackId: EntityId?,
            seedGenres: List<String>,
            now: Instant,
        ) = AdaptiveSessionAggregate(
            id = id,
            userId = userId,
            state = SessionState.ACTIVE,
            startedAt = now,
            lastActivityAt = now,
            endedAt = null,
            sessionSummary = null,
            energy = null,
            intensity = null,
            moodTags = emptyList(),
            attentionMode = attentionMode,
            seedTrackId = seedTrackId,
            seedGenres = seedGenres,
            queue = emptyList(),
            queueVersion = 0,
            // Version starts at INITIAL.next() (1) because start() is a creation command,
            // unlike PlaybackSessionAggregate.empty() which uses INITIAL (0) as a null-object
            // for sessions that haven't been explicitly created yet.
            version = AggregateVersion.INITIAL.next(),
        )
    }
}

data class AdaptiveQueueEntryData(
    val id: AdaptiveQueueEntryId,
    val trackId: TrackId,
    val position: Int,
    val addedReason: String?,
    val intentLabel: String?,
    val queueVersion: Long,
    val addedAt: Instant,
)
