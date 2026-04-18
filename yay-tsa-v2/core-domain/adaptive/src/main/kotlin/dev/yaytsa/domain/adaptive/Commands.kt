package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId

sealed interface AdaptiveCommand : Command {
    val sessionId: ListeningSessionId
}

data class StartListeningSession(
    override val sessionId: ListeningSessionId,
    val attentionMode: String,
    val seedTrackId: EntityId?,
    val seedGenres: List<String>,
) : AdaptiveCommand

data class EndListeningSession(
    override val sessionId: ListeningSessionId,
    val summary: String?,
) : AdaptiveCommand

data class UpdateSessionContext(
    override val sessionId: ListeningSessionId,
    val energy: Float?,
    val intensity: Float?,
    val moodTags: List<String>,
    val attentionMode: String,
) : AdaptiveCommand

data class RewriteQueueTail(
    override val sessionId: ListeningSessionId,
    val baseQueueVersion: Long,
    val keepFromPosition: Int,
    val newTail: List<NewQueueEntry>,
) : AdaptiveCommand

data class RecordPlaybackSignal(
    override val sessionId: ListeningSessionId,
    val signalId: String,
    val trackId: TrackId,
    val queueEntryId: AdaptiveQueueEntryId?,
    val signalType: String,
    val signalContext: String?,
) : AdaptiveCommand

data class NewQueueEntry(
    val id: AdaptiveQueueEntryId,
    val trackId: TrackId,
    val addedReason: String?,
    val intentLabel: String?,
)
