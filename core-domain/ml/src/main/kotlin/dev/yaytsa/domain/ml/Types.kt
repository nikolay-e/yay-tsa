package dev.yaytsa.domain.ml

import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

data class TrackFeatures(
    val trackId: TrackId,
    val bpm: Float?,
    val bpmConfidence: Float?,
    val musicalKey: String?,
    val keyConfidence: Float?,
    val energy: Float?,
    val loudnessIntegrated: Float?,
    val loudnessRange: Float?,
    val averageLoudness: Float?,
    val valence: Float?,
    val arousal: Float?,
    val danceability: Float?,
    val vocalInstrumental: Float?,
    val voiceGender: String?,
    val spectralComplexity: Float?,
    val dissonance: Float?,
    val onsetRate: Float?,
    val introDurationSec: Float?,
    val extractedAt: Instant,
    val extractorVersion: String,
    // Embeddings stored as float arrays
    val embeddingDiscogs: FloatArray?,
    val embeddingMusicnn: FloatArray?,
    val embeddingClap: FloatArray?,
    val embeddingMert: FloatArray?,
)

data class TasteProfile(
    val userId: UserId,
    val profile: String,
    val summaryText: String?,
    val rebuiltAt: Instant,
    val trackCount: Int,
    // Embeddings
    val embeddingMert: FloatArray?,
    val embeddingClap: FloatArray?,
)

data class UserTrackAffinity(
    val userId: UserId,
    val trackId: TrackId,
    val affinityScore: Double,
    val playCount: Int,
    val completionCount: Int,
    val skipCount: Int,
    val thumbsUpCount: Int,
    val thumbsDownCount: Int,
    val totalListenSec: Int,
    val lastSignalAt: Instant?,
    val updatedAt: Instant,
)
