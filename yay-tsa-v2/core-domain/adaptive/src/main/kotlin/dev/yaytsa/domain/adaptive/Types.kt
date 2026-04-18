package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

@JvmInline value class ListeningSessionId(
    val value: String,
)

data class ListeningSession(
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
)

@JvmInline value class AdaptiveQueueEntryId(
    val value: String,
)

data class AdaptiveQueueEntry(
    val id: AdaptiveQueueEntryId,
    val trackId: TrackId,
    val position: Int,
    val addedReason: String?,
    val intentLabel: String?,
    val status: String,
    val queueVersion: Long,
    val addedAt: Instant,
    val playedAt: Instant?,
)

data class PlaybackSignal(
    val id: String,
    val sessionId: ListeningSessionId,
    val trackId: TrackId,
    val queueEntryId: AdaptiveQueueEntryId?,
    val signalType: String,
    val context: String,
    val createdAt: Instant,
)

data class LlmDecision(
    val id: String,
    val sessionId: ListeningSessionId,
    val triggerType: String,
    val triggerSignalId: String?,
    val promptHash: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val modelId: String?,
    val latencyMs: Int?,
    val intent: String?,
    val edits: String?,
    val baseQueueVersion: Long?,
    val appliedQueueVersion: Long?,
    val validationResult: String?,
    val validationDetails: String?,
    val createdAt: Instant,
)
