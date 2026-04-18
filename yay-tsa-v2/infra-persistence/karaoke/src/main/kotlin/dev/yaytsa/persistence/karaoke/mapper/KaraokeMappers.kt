package dev.yaytsa.persistence.karaoke.mapper

import dev.yaytsa.domain.karaoke.KaraokeAsset
import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import dev.yaytsa.shared.TrackId

object KaraokeMappers {
    fun toDomain(entity: KaraokeAssetEntity): KaraokeAsset =
        KaraokeAsset(
            trackId = TrackId(entity.trackId.toString()),
            instrumentalPath = entity.instrumentalPath,
            vocalPath = entity.vocalPath,
            lyricsTiming = entity.lyricsTiming,
            readyAt = entity.readyAt,
        )
}
