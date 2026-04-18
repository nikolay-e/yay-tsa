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
@Table(name = "taste_profiles", schema = "core_v2_ml")
class TasteProfileEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID = UUID.randomUUID(),
    @Column(columnDefinition = "TEXT")
    val profile: String? = null,
    @Column(name = "summary_text")
    val summaryText: String? = null,
    @Column(name = "rebuilt_at", nullable = false)
    val rebuiltAt: Instant = Instant.now(),
    @Column(name = "track_count", nullable = false)
    val trackCount: Int = 0,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_mert", columnDefinition = "float[]")
    val embeddingMert: FloatArray? = null,
    @Convert(converter = FloatArrayAttributeConverter::class)
    @Column(name = "embedding_clap", columnDefinition = "float[]")
    val embeddingClap: FloatArray? = null,
)
