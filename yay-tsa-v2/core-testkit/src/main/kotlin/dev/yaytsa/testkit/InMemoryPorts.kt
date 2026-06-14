package dev.yaytsa.testkit

import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.adaptive.port.PlaybackSignalWritePort
import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.StoredIdempotencyRecord
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.AdaptiveSessionAggregate
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Genre
import dev.yaytsa.domain.library.Image
import dev.yaytsa.domain.library.SearchResults
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

// --- Adaptive ---

class InMemoryAdaptiveSessionRepository : AdaptiveSessionRepository {
    private val store = mutableMapOf<ListeningSessionId, AdaptiveSessionAggregate>()

    override fun find(sessionId: ListeningSessionId) = store[sessionId]

    override fun findActiveByUser(userId: UserId) = store.values.find { it.userId == userId && it.state == dev.yaytsa.domain.adaptive.SessionState.ACTIVE }

    override fun save(aggregate: AdaptiveSessionAggregate) {
        store[aggregate.id] = aggregate
    }
}

class InMemoryPlaybackSignalWritePort : PlaybackSignalWritePort {
    data class StoredSignal(
        val id: String,
        val sessionId: ListeningSessionId,
        val trackId: TrackId,
        val queueEntryId: AdaptiveQueueEntryId?,
        val signalType: String,
        val context: String?,
        val createdAt: Instant,
    )

    val signals = mutableListOf<StoredSignal>()

    override fun save(
        id: String,
        sessionId: ListeningSessionId,
        trackId: TrackId,
        queueEntryId: AdaptiveQueueEntryId?,
        signalType: String,
        context: String?,
        createdAt: Instant,
    ) {
        signals.add(StoredSignal(id, sessionId, trackId, queueEntryId, signalType, context, createdAt))
    }
}

// --- Auth ---

class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, UserAggregate>()

    override fun find(userId: UserId) = store[userId]

    override fun findByUsername(username: String) = store.values.find { it.username == username }

    override fun findByApiToken(token: String) = store.values.find { u -> u.apiTokens.any { it.token == token && !it.revoked } }

    override fun findAll(): List<UserAggregate> = store.values.toList()

    override fun save(aggregate: UserAggregate) {
        store[aggregate.id] = aggregate
    }
}

// --- Preferences ---

class InMemoryUserPreferencesRepository : UserPreferencesRepository {
    private val store = mutableMapOf<UserId, UserPreferencesAggregate>()

    override fun find(userId: UserId) = store[userId]

    override fun save(aggregate: UserPreferencesAggregate) {
        store[aggregate.userId] = aggregate
    }
}

// --- Playlists ---

class InMemoryPlaylistRepository : PlaylistRepository {
    private val store = mutableMapOf<PlaylistId, PlaylistAggregate>()

    override fun find(playlistId: PlaylistId) = store[playlistId]

    override fun save(aggregate: PlaylistAggregate) {
        store[aggregate.id] = aggregate
    }

    override fun delete(playlistId: PlaylistId) {
        store.remove(playlistId)
    }

    override fun findByOwner(userId: UserId) = store.values.filter { it.owner == userId }
}

// --- Playback ---

class InMemoryPlaybackSessionRepository : PlaybackSessionRepository {
    private val store = mutableMapOf<Pair<UserId, SessionId>, PlaybackSessionAggregate>()

    override fun find(
        userId: UserId,
        sessionId: SessionId,
    ) = store[userId to sessionId]

    override fun save(aggregate: PlaybackSessionAggregate) {
        store[aggregate.userId to aggregate.sessionId] = aggregate
    }
}

// --- Library ---

class InMemoryLibraryQueryPort : LibraryQueryPort {
    val tracks = mutableMapOf<EntityId, Track>()
    val albums = mutableMapOf<EntityId, Album>()
    val artists = mutableMapOf<EntityId, Artist>()
    val genres = mutableMapOf<EntityId, List<Genre>>()
    val images = mutableMapOf<EntityId, Image>()
    val trackFilePaths = mutableMapOf<EntityId, String>()

    override fun getTrack(trackId: EntityId) = tracks[trackId]

    override fun getAlbum(albumId: EntityId) = albums[albumId]

    override fun getArtist(artistId: EntityId) = artists[artistId]

    override fun getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String> =
        ids
            .mapNotNull { id ->
                (tracks[id]?.name ?: albums[id]?.name ?: artists[id]?.name)?.let { id to it }
            }.toMap()

    override fun browseArtists(
        limit: Int,
        offset: Int,
    ) = artists.values
        .sortedBy { it.name }
        .drop(offset)
        .take(limit)

    override fun browseAlbums(
        limit: Int,
        offset: Int,
    ) = albums.values
        .sortedBy { it.name }
        .drop(offset)
        .take(limit)

    override fun browseAlbumsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<Album> {
        if (excludedGenreNames.isEmpty()) return browseAlbums(limit, offset)
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        return albums.values
            .filter { album -> albumHasKeptTrack(album.id, lowered) }
            .sortedBy { it.name }
            .drop(maxOf(offset, 0))
            .take(maxOf(limit, 1))
    }

    override fun countAlbumsExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return albums.size
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        return albums.values.count { albumHasKeptTrack(it.id, lowered) }
    }

    override fun browseArtistsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<Artist> {
        if (excludedGenreNames.isEmpty()) return browseArtists(limit, offset)
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        return artists.values
            .filter { artist -> artistHasKeptTrack(artist.id, lowered) }
            .sortedBy { it.name }
            .drop(maxOf(offset, 0))
            .take(maxOf(limit, 1))
    }

    override fun countArtistsExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return artists.size
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        return artists.values.count { artistHasKeptTrack(it.id, lowered) }
    }

    private fun albumHasKeptTrack(
        albumId: EntityId,
        lowered: Set<String>,
    ): Boolean = tracks.values.any { it.albumId == albumId && !trackHasAnyGenre(it, lowered) }

    private fun artistHasKeptTrack(
        artistId: EntityId,
        lowered: Set<String>,
    ): Boolean = tracks.values.any { it.albumArtistId == artistId && !trackHasAnyGenre(it, lowered) }

    override fun browseAlbumsByCreatedDesc(
        limit: Int,
        offset: Int,
    ) = albums.values
        .sortedByDescending { it.createdAt }
        .drop(offset)
        .take(limit)

    override fun browseAlbumsRandom(limit: Int) = albums.values.shuffled().take(limit)

    override fun browseAlbumsByYearRange(
        fromYear: Int,
        toYear: Int,
        limit: Int,
        offset: Int,
    ): List<Album> {
        val range = minOf(fromYear, toYear)..maxOf(fromYear, toYear)
        val matching = albums.values.filter { it.releaseDate?.year in range }.sortedBy { it.releaseDate }
        val ordered = if (fromYear <= toYear) matching else matching.reversed()
        return ordered.drop(offset).take(limit)
    }

    override fun browseAlbumsByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Album> {
        val albumIds =
            browseTracksByGenreNames(listOf(genre))
                .mapNotNull { it.albumId }
                .toSet()
        return albums.values
            .filter { it.id in albumIds }
            .sortedBy { it.name }
            .drop(offset)
            .take(limit)
    }

    override fun browseAlbumsByArtist(artistId: EntityId) = albums.values.filter { it.artistId == artistId }.sortedBy { it.name }

    override fun browseTracksByAlbum(albumId: EntityId) = tracks.values.filter { it.albumId == albumId }.sortedBy { it.trackNumber }

    override fun browseTracksByArtist(
        artistId: EntityId,
        limit: Int,
        offset: Int,
    ) = tracks.values
        .filter { it.albumArtistId == artistId }
        .sortedWith(compareBy({ it.albumId?.value }, { it.discNumber }, { it.trackNumber }))
        .drop(maxOf(offset, 0))
        .take(maxOf(limit, 1))

    override fun countTracksByArtist(artistId: EntityId): Int = tracks.values.count { it.albumArtistId == artistId }

    override fun getTracksByIds(trackIds: List<EntityId>): List<dev.yaytsa.domain.library.Track> = trackIds.mapNotNull { tracks[it] }

    override fun browseTracksByGenreNames(genreNames: Collection<String>): List<Track> {
        if (genreNames.isEmpty()) return emptyList()
        val lowered = genreNames.map { it.lowercase() }.toSet()
        return tracks.values
            .filter { track ->
                val byColumn = track.genre?.trim()?.lowercase() in lowered
                val byJoin = (genres[track.id] ?: emptyList()).any { it.name.trim().lowercase() in lowered }
                byColumn || byJoin
            }.sortedBy { (it.sortName ?: it.name).lowercase() }
    }

    private fun trackHasAnyGenre(
        track: Track,
        lowered: Set<String>,
    ): Boolean {
        val byColumn = track.genre?.trim()?.lowercase() in lowered
        val byJoin = (genres[track.id] ?: emptyList()).any { it.name.trim().lowercase() in lowered }
        return byColumn || byJoin
    }

    override fun browseTracksExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> {
        if (excludedGenreNames.isEmpty()) return browseTracks(limit, offset, sortBy, sortOrder)
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        val kept = tracks.values.filter { !trackHasAnyGenre(it, lowered) }.sortedBy { it.name.lowercase() }
        val ordered = if (sortOrder.equals("Descending", ignoreCase = true)) kept.reversed() else kept
        return ordered.drop(maxOf(offset, 0)).take(maxOf(limit, 1))
    }

    override fun countTracksExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return tracks.size
        val lowered = excludedGenreNames.map { it.lowercase() }.toSet()
        return tracks.values.count { !trackHasAnyGenre(it, lowered) }
    }

    override fun browseTracksRandom(limit: Int) = tracks.values.shuffled().take(limit)

    override fun browseTracks(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> {
        val sorted = tracks.values.sortedBy { it.name.lowercase() }
        val ordered = if (sortOrder.equals("Descending", ignoreCase = true)) sorted.reversed() else sorted
        return ordered.drop(maxOf(offset, 0)).take(maxOf(limit, 1))
    }

    override fun searchText(
        query: String,
        limit: Int,
        offset: Int,
        excludedGenres: Collection<String>,
    ): SearchResults {
        val q = query.lowercase()
        val lowered = excludedGenres.map { it.lowercase() }.toSet()
        return SearchResults(
            artists =
                artists.values
                    .filter { it.name.lowercase().contains(q) }
                    .filter { lowered.isEmpty() || artistHasKeptTrack(it.id, lowered) }
                    .take(limit),
            albums =
                albums.values
                    .filter { it.name.lowercase().contains(q) }
                    .filter { lowered.isEmpty() || albumHasKeptTrack(it.id, lowered) }
                    .take(limit),
            tracks =
                tracks.values
                    .filter { it.name.lowercase().contains(q) }
                    .filter { lowered.isEmpty() || !trackHasAnyGenre(it, lowered) }
                    .take(limit),
        )
    }

    override fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> {
        val knownIds = tracks.keys.map { TrackId(it.value) }.toSet()
        return trackIds.filter { it in knownIds }.toSet()
    }

    override fun getGenres(entityId: EntityId) = genres[entityId] ?: emptyList()

    override fun getPrimaryImage(entityId: EntityId): Image? = images[entityId]

    override fun resolveTrackFilePath(trackId: EntityId): String? = trackFilePaths[trackId]

    override fun countTracks(): Int = tracks.size

    override fun countAlbums(): Int = albums.size

    override fun countArtists(): Int = artists.size

    override fun countTextSearchTracks(query: String): Int {
        val q = query.lowercase()
        return tracks.values.count { it.name.lowercase().contains(q) }
    }

    override fun countTextSearchArtists(query: String): Int {
        val q = query.lowercase()
        return artists.values.count { it.name.lowercase().contains(q) }
    }

    override fun countTextSearchAlbums(query: String): Int {
        val q = query.lowercase()
        return albums.values.count { it.name.lowercase().contains(q) }
    }

    override fun countAlbumsByArtistIds(artistIds: Set<EntityId>): Map<EntityId, Int> {
        if (artistIds.isEmpty()) return emptyMap()
        return artistIds.associateWith { aid -> albums.values.count { it.artistId == aid } }
    }
}

// --- Shared infrastructure ---

class InMemoryIdempotencyStore : IdempotencyStore {
    private data class Key(
        val userId: UserId,
        val commandType: String,
        val key: IdempotencyKey,
    )

    private val store = mutableMapOf<Key, StoredIdempotencyRecord>()

    override fun find(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
    ) = store[Key(userId, commandType, key)]

    override fun store(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
        payloadHash: String,
        resultVersion: Long,
    ) {
        store[Key(userId, commandType, key)] = StoredIdempotencyRecord(payloadHash, resultVersion)
    }
}

class FixedClock(
    var time: Instant = Instant.parse("2025-01-01T12:00:00Z"),
) : Clock {
    override fun now() = time

    fun advance(seconds: Long) {
        time = time.plusSeconds(seconds)
    }
}

class RecordingOutbox : OutboxPort {
    val notifications = mutableListOf<DomainNotification>()

    override fun enqueue(notification: DomainNotification) {
        notifications.add(notification)
    }

    fun clear() = notifications.clear()
}

class DirectTransactionalExecutor : TransactionalCommandExecutor {
    override fun <T> execute(block: () -> CommandResult<T>) = block()
}
