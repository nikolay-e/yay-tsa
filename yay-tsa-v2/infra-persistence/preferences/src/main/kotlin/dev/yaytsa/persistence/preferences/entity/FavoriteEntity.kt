package dev.yaytsa.persistence.preferences.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class FavoriteEntityId(
    var userId: String = "",
    var trackId: String = "",
) : Serializable

@Entity
@Table(name = "favorites", schema = "core_v2_preferences")
@IdClass(FavoriteEntityId::class)
class FavoriteEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Id
    @Column(name = "track_id", nullable = false)
    var trackId: String = "",
    @Column(name = "favorited_at", nullable = false)
    var favoritedAt: Instant = Instant.now(),
    @Column(name = "position", nullable = false)
    var position: Int = 0,
)
