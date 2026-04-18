package dev.yaytsa.domain.karaoke

import dev.yaytsa.shared.TrackId
import java.time.Instant

data class KaraokeAsset(
    val trackId: TrackId,
    val instrumentalPath: String?,
    val vocalPath: String?,
    val lyricsTiming: String?,
    val readyAt: Instant?,
)
