package dev.yaytsa.adaptershared

import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId

data class TrackLookups(
    val albumNames: Map<EntityId, String> = emptyMap(),
    val artistNames: Map<EntityId, String> = emptyMap(),
)

fun Track.toJellyfinBaseItem(
    favTrackIds: Set<String> = emptySet(),
    lookups: TrackLookups = TrackLookups(),
): BaseItem {
    val artistName = albumArtistId?.let { lookups.artistNames[it] }
    return BaseItem(
        id = id.value,
        name = name,
        type = "Audio",
        album = albumId?.let { lookups.albumNames[it] },
        albumId = albumId?.value,
        artists = artistName?.let { listOf(it) },
        artistItems =
            if (artistName != null && albumArtistId != null) {
                listOf(NameIdPair(artistName, albumArtistId!!.value))
            } else {
                null
            },
        runTimeTicks = msToTicks(durationMs),
        indexNumber = trackNumber,
        parentIndexNumber = discNumber,
        imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
        userData = UserItemData(isFavorite = id.value in favTrackIds),
        genres = genre?.let { listOf(it) },
        sortName = sortName,
        parentId = albumId?.value,
    )
}

fun Track.toSubsonicChild(lookups: TrackLookups = TrackLookups()): ChildElement =
    ChildElement(
        id = id.value,
        parent = albumId?.value,
        title = name,
        album = albumId?.let { lookups.albumNames[it] },
        artist = albumArtistId?.let { lookups.artistNames[it] },
        duration = durationMs?.let { (it / 1000).toInt() },
        bitRate = bitrate,
        track = trackNumber,
        discNumber = discNumber,
        year = year,
        genre = genre,
        coverArt = coverImagePath?.let { id.value },
        albumId = albumId?.value,
        artistId = albumArtistId?.value,
    )

fun Track.toMcpJson(): Map<String, Any?> =
    mapOf(
        "trackId" to id.value,
        "name" to name,
        "albumId" to albumId?.value,
        "albumArtistId" to albumArtistId?.value,
        "trackNumber" to trackNumber,
        "discNumber" to discNumber,
        "durationMs" to durationMs,
        "bitrate" to bitrate,
        "year" to year,
        "genre" to genre,
        "coverImagePath" to coverImagePath,
    )
