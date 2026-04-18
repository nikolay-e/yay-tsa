package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.TrackId

data class PlaylistDeps(
    val knownTrackIds: Set<TrackId>,
)
