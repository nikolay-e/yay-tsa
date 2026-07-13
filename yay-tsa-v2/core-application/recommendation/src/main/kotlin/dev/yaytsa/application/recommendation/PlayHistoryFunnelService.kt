package dev.yaytsa.application.recommendation

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId

/**
 * Folds per-track play history into album/artist surfaces shared by the Jellyfin and Subsonic
 * adapters. Tracks whose primary genre is excluded are dropped before collapsing (a per-track
 * primary-genre approximation of the browse genre-exclusion, adequate because audiobook tracks
 * carry an audiobook genre).
 */
class PlayHistoryFunnelService(
    private val playHistoryQuery: PlayHistoryQueryPort,
    private val libraryQueries: LibraryQueries,
) {
    // Tracks the user most recently played (most-recent-first), from play history. getTracksByIds
    // preserves the recency order and drops vanished tracks.
    fun recentlyPlayedTracks(
        userId: UserId,
        excludedGenres: Collection<String> = emptyList(),
    ): List<Track> {
        val ids = playHistoryQuery.recentlyPlayedTrackIds(userId, PLAY_HISTORY_TRACK_POOL).map { EntityId(it.value) }
        return dropExcludedGenres(libraryQueries.getTracksByIds(ids), excludedGenres)
    }

    // Albums ordered by the most recent play of any of their tracks. Because recentlyPlayedTracks is
    // already recency-ordered, an album's first appearance is its most recent play, so distinct()
    // preserves that ordering. Empty when there is no (non-excluded) play history, so callers can
    // fall back to a deterministic browse.
    fun recentlyPlayedAlbums(
        userId: UserId,
        limit: Int,
        offset: Int = 0,
        excludedGenres: Collection<String> = emptyList(),
    ): List<Album> {
        val orderedAlbumIds = recentlyPlayedTracks(userId, excludedGenres).mapNotNull { it.albumId }.distinct()
        return libraryQueries.getAlbumsByIds(pageOf(orderedAlbumIds, limit, offset))
    }

    // Artists ordered by the most recent play of any of their tracks (album artist), same principle.
    fun recentlyPlayedArtists(
        userId: UserId,
        limit: Int,
        excludedGenres: Collection<String> = emptyList(),
    ): List<Artist> {
        val orderedArtistIds = recentlyPlayedTracks(userId, excludedGenres).mapNotNull { it.albumArtistId }.distinct()
        return libraryQueries.getArtistsByIds(pageOf(orderedArtistIds, limit, offset = 0))
    }

    fun mostPlayedAlbums(
        userId: UserId,
        limit: Int,
        offset: Int = 0,
        excludedGenres: Collection<String> = emptyList(),
    ): List<Album> {
        val playCounts = playHistoryQuery.mostPlayedTrackCounts(userId, PLAY_HISTORY_TRACK_POOL)
        if (playCounts.isEmpty()) return emptyList()
        val playCountByTrackId = playCounts.associate { it.trackId.value to it.playCount }
        val playedTracks =
            dropExcludedGenres(
                libraryQueries.getTracksByIds(playCounts.map { EntityId(it.trackId.value) }),
                excludedGenres,
            )
        val albumIds =
            playedTracks
                .mapNotNull { track -> track.albumId?.let { it to (playCountByTrackId[track.id.value] ?: 0L) } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, counts) -> counts.sum() }
                .entries
                .sortedWith(compareByDescending<Map.Entry<EntityId, Long>> { it.value }.thenBy { it.key.value })
                .map { it.key }
        return libraryQueries.getAlbumsByIds(pageOf(albumIds, limit, offset))
    }

    private fun dropExcludedGenres(
        tracks: List<Track>,
        excludedGenres: Collection<String>,
    ): List<Track> {
        if (excludedGenres.isEmpty()) return tracks
        val excluded = excludedGenres.map { it.lowercase() }.toSet()
        return tracks.filter { it.genre?.lowercase() !in excluded }
    }

    private fun pageOf(
        ids: List<EntityId>,
        limit: Int,
        offset: Int,
    ): List<EntityId> =
        ids
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceIn(1, MAX_PAGE_SIZE))

    companion object {
        // How many distinct played tracks to fold into album/artist surfaces. Bounds the
        // play-history scan while covering enough history to fill a page after collapsing to
        // albums/artists and dropping excluded-genre tracks (many tracks map to one album/artist).
        private const val PLAY_HISTORY_TRACK_POOL = 500

        private const val MAX_PAGE_SIZE = 500
    }
}
