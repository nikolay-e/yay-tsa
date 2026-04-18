package dev.yaytsa.persistence.playlists.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class PlaylistTrackEntityId(
    var playlistId: String = "",
    var position: Int = 0,
) : Serializable

@Entity
@Table(name = "playlist_tracks", schema = "core_v2_playlists")
@IdClass(PlaylistTrackEntityId::class)
class PlaylistTrackEntity(
    @Id
    @Column(name = "playlist_id", nullable = false)
    var playlistId: String = "",
    @Id
    @Column(name = "position", nullable = false)
    var position: Int = 0,
    @Column(name = "track_id", nullable = false)
    var trackId: String = "",
    @Column(name = "added_at", nullable = false)
    var addedAt: Instant = Instant.now(),
)
