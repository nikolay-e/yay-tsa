package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

data class PlaylistAggregate(
    val id: PlaylistId,
    val owner: UserId,
    val name: String,
    val description: String?,
    val isPublic: Boolean,
    val tracks: List<PlaylistEntry>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: AggregateVersion,
)

data class PlaylistEntry(
    val trackId: TrackId,
    val addedAt: Instant,
)
