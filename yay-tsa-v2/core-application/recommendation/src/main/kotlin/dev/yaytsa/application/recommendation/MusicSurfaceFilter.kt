package dev.yaytsa.application.recommendation

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId

class MusicSurfaceFilter(
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
) {
    fun filter(
        tracks: List<Track>,
        userId: UserId,
    ): List<Track> = filterRedLines(tracks.filterNot { isAudiobookTrack(it) }, userId)

    fun isAudiobookTrack(track: Track): Boolean = Companion.isAudiobookTrack(track)

    fun filterRedLines(
        tracks: List<Track>,
        userId: UserId,
    ): List<Track> = filterRedLines(tracks, loadRedLineTerms(userId))

    fun filterRedLines(
        tracks: List<Track>,
        redLineTerms: List<String>,
    ): List<Track> {
        if (redLineTerms.isEmpty() || tracks.isEmpty()) return tracks
        val lookups = entityNameLookups(tracks)
        return tracks.filterNot { matchesRedLine(it, redLineTerms, lookups) }
    }

    fun isBlocked(
        track: Track,
        redLineTerms: List<String>,
    ): Boolean = isAudiobookTrack(track) || matchesRedLine(track, redLineTerms, entityNameLookups(listOf(track)))

    fun loadRedLineTerms(userId: UserId): List<String> =
        preferencesQueries
            .find(userId)
            ?.preferenceContract
            ?.redLines
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun entityNameLookups(tracks: List<Track>): EntityNameLookups {
        if (tracks.isEmpty()) return EntityNameLookups()
        val albumIds = tracks.mapNotNull { it.albumId }.toSet()
        val artistIds = tracks.mapNotNull { it.albumArtistId }.toSet()
        return EntityNameLookups(
            albumNames = libraryQueries.getEntityNamesByIds(albumIds),
            artistNames = libraryQueries.getEntityNamesByIds(artistIds),
        )
    }

    private fun matchesRedLine(
        track: Track,
        terms: List<String>,
        lookups: EntityNameLookups,
    ): Boolean {
        if (terms.isEmpty()) return false
        val haystack =
            buildString {
                append(track.name.lowercase())
                track.genre?.let { append(' ').append(it.lowercase()) }
                track.albumId?.let { lookups.albumNames[it]?.let { name -> append(' ').append(name.lowercase()) } }
                track.albumArtistId?.let { lookups.artistNames[it]?.let { name -> append(' ').append(name.lowercase()) } }
            }
        return terms.any { it in haystack }
    }

    private data class EntityNameLookups(
        val albumNames: Map<EntityId, String> = emptyMap(),
        val artistNames: Map<EntityId, String> = emptyMap(),
    )

    companion object {
        private val AUDIOBOOK_GENRES = setOf("audiobook", "audiobooks")

        fun isAudiobookTrack(track: Track): Boolean {
            val genre = track.genre?.trim()?.lowercase() ?: return false
            return genre in AUDIOBOOK_GENRES
        }
    }
}
