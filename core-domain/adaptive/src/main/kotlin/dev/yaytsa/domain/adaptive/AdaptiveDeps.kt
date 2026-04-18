package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.TrackId

data class AdaptiveDeps(
    val knownTrackIds: Set<TrackId>,
)
