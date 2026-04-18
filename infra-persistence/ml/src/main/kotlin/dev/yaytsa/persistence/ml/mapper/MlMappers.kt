package dev.yaytsa.persistence.ml.mapper

import dev.yaytsa.domain.ml.TasteProfile
import dev.yaytsa.domain.ml.TrackFeatures
import dev.yaytsa.domain.ml.UserTrackAffinity
import dev.yaytsa.persistence.ml.entity.TasteProfileEntity
import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import dev.yaytsa.persistence.ml.entity.UserTrackAffinityEntity
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

object MlMappers {
    fun toDomain(entity: TrackFeaturesEntity): TrackFeatures =
        TrackFeatures(
            trackId = TrackId(entity.trackId.toString()),
            bpm = entity.bpm,
            bpmConfidence = entity.bpmConfidence,
            musicalKey = entity.musicalKey,
            keyConfidence = entity.keyConfidence,
            energy = entity.energy,
            loudnessIntegrated = entity.loudnessIntegrated,
            loudnessRange = entity.loudnessRange,
            averageLoudness = entity.averageLoudness,
            valence = entity.valence,
            arousal = entity.arousal,
            danceability = entity.danceability,
            vocalInstrumental = entity.vocalInstrumental,
            voiceGender = entity.voiceGender,
            spectralComplexity = entity.spectralComplexity,
            dissonance = entity.dissonance,
            onsetRate = entity.onsetRate,
            introDurationSec = entity.introDurationSec,
            embeddingDiscogs = entity.embeddingDiscogs,
            embeddingMusicnn = entity.embeddingMusicnn,
            embeddingClap = entity.embeddingClap,
            embeddingMert = entity.embeddingMert,
            extractedAt = entity.extractedAt,
            extractorVersion = entity.extractorVersion,
        )

    fun toDomain(entity: TasteProfileEntity): TasteProfile =
        TasteProfile(
            userId = UserId(entity.userId.toString()),
            profile = entity.profile ?: "{}",
            summaryText = entity.summaryText,
            rebuiltAt = entity.rebuiltAt,
            trackCount = entity.trackCount,
            embeddingMert = entity.embeddingMert,
            embeddingClap = entity.embeddingClap,
        )

    fun toDomain(entity: UserTrackAffinityEntity): UserTrackAffinity =
        UserTrackAffinity(
            userId = UserId(entity.userId.toString()),
            trackId = TrackId(entity.trackId.toString()),
            affinityScore = entity.affinityScore,
            playCount = entity.playCount,
            completionCount = entity.completionCount,
            skipCount = entity.skipCount,
            thumbsUpCount = entity.thumbsUpCount,
            thumbsDownCount = entity.thumbsDownCount,
            totalListenSec = entity.totalListenSec,
            lastSignalAt = entity.lastSignalAt,
            updatedAt = entity.updatedAt,
        )
}
