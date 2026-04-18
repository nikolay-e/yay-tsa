package dev.yaytsa.persistence.adaptive.mapper

import dev.yaytsa.domain.adaptive.AdaptiveQueueEntry
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.ListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.LlmDecision
import dev.yaytsa.domain.adaptive.PlaybackSignal
import dev.yaytsa.domain.adaptive.SessionState
import dev.yaytsa.persistence.adaptive.entity.AdaptiveQueueEntryEntity
import dev.yaytsa.persistence.adaptive.entity.ListeningSessionEntity
import dev.yaytsa.persistence.adaptive.entity.LlmDecisionEntity
import dev.yaytsa.persistence.adaptive.entity.PlaybackSignalEntity
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

object AdaptiveMappers {
    fun toDomain(entity: ListeningSessionEntity): ListeningSession =
        ListeningSession(
            id = ListeningSessionId(entity.id.toString()),
            userId = UserId(entity.userId.toString()),
            state =
                if (entity.state == null) {
                    SessionState.ACTIVE
                } else {
                    SessionState.valueOf(entity.state)
                },
            startedAt = entity.startedAt,
            lastActivityAt = entity.lastActivityAt,
            endedAt = entity.endedAt,
            sessionSummary = entity.sessionSummary,
            energy = entity.energy,
            intensity = entity.intensity,
            moodTags = entity.moodTags,
            attentionMode = entity.attentionMode,
            seedTrackId = entity.seedTrackId?.let { EntityId(it.toString()) },
            seedGenres = entity.seedGenres,
        )

    fun toDomain(entity: AdaptiveQueueEntryEntity): AdaptiveQueueEntry =
        AdaptiveQueueEntry(
            id = AdaptiveQueueEntryId(entity.id.toString()),
            trackId = TrackId(entity.trackId.toString()),
            position = entity.position,
            addedReason = entity.addedReason,
            intentLabel = entity.intentLabel,
            status = entity.status,
            queueVersion = entity.queueVersion,
            addedAt = entity.addedAt,
            playedAt = entity.playedAt,
        )

    fun toDomain(entity: PlaybackSignalEntity): PlaybackSignal =
        PlaybackSignal(
            id = entity.id.toString(),
            sessionId = ListeningSessionId(entity.sessionId.toString()),
            trackId = TrackId(entity.trackId.toString()),
            queueEntryId = entity.queueEntryId?.let { AdaptiveQueueEntryId(it.toString()) },
            signalType = entity.signalType,
            context = entity.context ?: "{}",
            createdAt = entity.createdAt,
        )

    fun toDomain(entity: LlmDecisionEntity): LlmDecision =
        LlmDecision(
            id = entity.id.toString(),
            sessionId = ListeningSessionId(entity.sessionId.toString()),
            triggerType = entity.triggerType,
            triggerSignalId = entity.triggerSignalId?.toString(),
            promptHash = entity.promptHash,
            promptTokens = entity.promptTokens,
            completionTokens = entity.completionTokens,
            modelId = entity.modelId,
            latencyMs = entity.latencyMs,
            intent = entity.intent,
            edits = entity.edits,
            baseQueueVersion = entity.baseQueueVersion,
            appliedQueueVersion = entity.appliedQueueVersion,
            validationResult = entity.validationResult,
            validationDetails = entity.validationDetails,
            createdAt = entity.createdAt,
        )
}
