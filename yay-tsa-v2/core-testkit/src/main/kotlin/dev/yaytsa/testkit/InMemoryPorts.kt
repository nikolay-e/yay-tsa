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

    override fun browseAlbumsByArtist(artistId: EntityId) = albums.values.filter { it.artistId == artistId }.sortedBy { it.name }

    override fun browseTracksByAlbum(albumId: EntityId) = tracks.values.filter { it.albumId == albumId }.sortedBy { it.trackNumber }

    override fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults {
        val q = query.lowercase()
        return SearchResults(
            artists = artists.values.filter { it.name.lowercase().contains(q) }.take(limit),
            albums = albums.values.filter { it.name.lowercase().contains(q) }.take(limit),
            tracks = tracks.values.filter { it.name.lowercase().contains(q) }.take(limit),
        )
    }

    override fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> {
        val knownIds = tracks.keys.map { TrackId(it.value) }.toSet()
        return trackIds.filter { it in knownIds }.toSet()
    }

    override fun getGenres(entityId: EntityId) = genres[entityId] ?: emptyList()

    override fun getPrimaryImage(entityId: EntityId): Image? = images[entityId]

    override fun resolveTrackFilePath(trackId: EntityId): String? = trackFilePaths[trackId]
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
