package dev.yaytsa.application.karaoke.port

import dev.yaytsa.shared.TrackId

// A user-requested separation must not wait behind the scheduled batch backlog
// (hundreds of tracks = hours); implementations process the track ahead of it.
interface KaraokeOnDemandPort {
    fun requestImmediate(trackId: TrackId)
}
