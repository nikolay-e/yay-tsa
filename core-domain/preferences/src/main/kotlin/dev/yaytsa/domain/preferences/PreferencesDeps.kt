package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.TrackId

data class PreferencesDeps(
    val knownTrackIds: Set<TrackId>,
)
