package dev.yaytsa.persistence.ml.entity

import dev.yaytsa.persistence.shared.converter.FloatArrayAttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "track_features", schema = "core_v2_ml")
class TrackFeaturesEntity(
    @Id
    @Column(name = "track_id")
    val trackId: UUID = UUID.randomUUID(),
    val bpm: Float? = null,
    @Column(name = "bpm_confidence")
    val bpmConfidence: Float? = null,
    @Column(name = "musical_key")
    val musicalKey: String? = null,
    @Column(name = "key_confidence")
    val keyConfidence: Float? = null,
    val energy: Float? = null,
    @Column(name = "loudness_integrated")
    val loudnessIntegrated: Float? = null,
    @Column(name = "loudness_range")
    val loudnessRange: Float? = null,
    @Column(name = "average_loudness")
    val averageLoudness: Float? = null,
    val valence: Float? = null,
    val arousal: Float? = null,
    val danceability: Float? = null,
    @Column(name = "vocal_instrumental")
    val vocalInstrumental: Float? = null,
    @Column(name = "voice_gender")
    val voiceGender: String? = null,
    @Column(name = "spectral_complexity")
    val spectralComplexity: Float? = null,
    val dissonance: Float? = null,
    @Column(name = "onset_rate")
    val onsetRate: Float? = null,
    @Column(name = "intro_duration_sec")
    val introDurationSec: Float? = null,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_discogs", columnDefinition = "float[]")
    val embeddingDiscogs: FloatArray? = null,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_musicnn", columnDefinition = "float[]")
    val embeddingMusicnn: FloatArray? = null,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_clap", columnDefinition = "float[]")
    val embeddingClap: FloatArray? = null,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_mert", columnDefinition = "float[]")
    val embeddingMert: FloatArray? = null,
    @Column(name = "extracted_at", nullable = false)
    val extractedAt: Instant = Instant.now(),
    @Column(name = "extractor_version", nullable = false)
    val extractorVersion: String = "",
)
