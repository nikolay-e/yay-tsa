package dev.yaytsa.adaptermpd

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import org.springframework.stereotype.Component
import java.util.Locale

@Component
class MpdTrackFormatter(
    private val libraryQueries: LibraryQueries,
) {
    fun namesFor(tracks: List<Track>): Map<EntityId, String> {
        val ids = (tracks.mapNotNull { it.albumArtistId } + tracks.mapNotNull { it.albumId }).toSet()
        return libraryQueries.getEntityNamesByIds(ids)
    }

    fun block(track: Track): String = block(track, namesFor(listOf(track)))

    fun block(
        track: Track,
        names: Map<EntityId, String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("file: ${track.id.value}")
        val artistName = track.albumArtistId?.let { names[it] }
        if (artistName != null) {
            sb.appendLine("Artist: $artistName")
            sb.appendLine("AlbumArtist: $artistName")
        }
        track.albumId?.let { names[it] }?.let { sb.appendLine("Album: $it") }
        sb.appendLine("Title: ${track.name}")
        track.trackNumber?.let { sb.appendLine("Track: $it") }
        track.year?.let { sb.appendLine("Date: $it") }
        track.genre?.let { sb.appendLine("Genre: $it") }
        track.durationMs?.let {
            sb.appendLine("Time: ${it / 1000}")
            sb.appendLine("duration: ${String.format(Locale.ROOT, "%.3f", it / 1000.0)}")
        }
        return sb.toString()
    }
}
