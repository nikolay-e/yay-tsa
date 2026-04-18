package dev.yaytsa.persistence.playlists.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "playlists", schema = "core_v2_playlists")
class PlaylistEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: String = "",
    @Column(name = "owner", nullable = false)
    var owner: String = "",
    @Column(name = "name", nullable = false)
    var name: String = "",
    @Column(name = "description")
    var description: String? = null,
    @Column(name = "is_public", nullable = false)
    var isPublic: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
