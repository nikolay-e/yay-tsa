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

fun Track.toMpdLines(): String {
    val sb = StringBuilder()
    sb.appendLine("file: ${id.value}")
    sb.appendLine("Title: $name")
    genre?.let { sb.appendLine("Genre: $it") }
    trackNumber?.let { sb.appendLine("Track: $it") }
    year?.let { sb.appendLine("Date: $it") }
    durationMs?.let { sb.appendLine("Time: ${it / 1000}") }
    durationMs?.let { sb.appendLine("duration: ${it.toDouble() / 1000}") }
    albumArtistId?.let { sb.appendLine("AlbumArtistId: ${it.value}") }
    albumId?.let { sb.appendLine("AlbumId: ${it.value}") }
    return sb.toString()
}

fun Track.toMpdSummaryLines(): String {
    val sb = StringBuilder()
    sb.appendLine("file: ${id.value}")
    sb.appendLine("Title: $name")
    durationMs?.let { sb.appendLine("Time: ${it / 1000}") }
    albumArtistId?.let { sb.appendLine("AlbumArtistId: ${it.value}") }
    return sb.toString()
}

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
