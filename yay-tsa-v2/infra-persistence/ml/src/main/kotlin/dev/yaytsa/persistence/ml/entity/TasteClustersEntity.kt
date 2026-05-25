package dev.yaytsa.persistence.ml.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class TasteClusterKey(
    val userId: UUID = UUID.randomUUID(),
    val clusterId: Int = 0,
) : Serializable

@Entity
@Table(name = "taste_clusters", schema = "core_v2_ml")
@IdClass(TasteClusterKey::class)
class TasteClustersEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "cluster_id")
    val clusterId: Int = 0,
    @Column(name = "size", nullable = false)
    val size: Int = 0,
    @Column(name = "representative_track_id", nullable = false)
    val representativeTrackId: UUID = UUID.randomUUID(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
