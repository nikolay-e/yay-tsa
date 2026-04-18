package dev.yaytsa.domain.playback

import dev.yaytsa.shared.TrackId
import java.time.Duration

data class PlaybackDeps(
    val knownTrackIds: Set<TrackId>,
    val currentTrackDuration: Duration?,
)
