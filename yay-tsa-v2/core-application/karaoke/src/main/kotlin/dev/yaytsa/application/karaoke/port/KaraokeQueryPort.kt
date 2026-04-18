package dev.yaytsa.application.karaoke.port

import dev.yaytsa.domain.karaoke.KaraokeAsset
import dev.yaytsa.shared.TrackId

interface KaraokeQueryPort {
    fun getAsset(trackId: TrackId): KaraokeAsset?

    fun getReadyTrackIds(): Set<TrackId>
}
