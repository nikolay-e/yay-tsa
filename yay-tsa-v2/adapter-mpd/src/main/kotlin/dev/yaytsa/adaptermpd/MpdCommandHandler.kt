package dev.yaytsa.adaptermpd

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.MpdFailureTranslator
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.MoveQueueEntry
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.RemoveFromQueue
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.Stop
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Component
class MpdCommandHandler(
    private val playbackQueries: PlaybackQueries,
    private val playbackUseCases: PlaybackUseCases,
    private val libraryQueries: LibraryQueries,
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val trackFormatter: MpdTrackFormatter,
    @Qualifier("mpdCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val failureTranslator: MpdFailureTranslator,
) {
    companion object {
        private val MPD_USER = UserId("mpd-default")
        private val MPD_SESSION = SessionId("mpd-default")
        private val MPD_DEVICE = DeviceId("mpd")
        private val MPD_LEASE_DURATION = Duration.ofHours(6)
        private const val BROWSE_PAGE_SIZE = 200
    }

    data class SubsystemSnapshot(
        val playlistVersion: Int,
        val playerToken: String,
    )

    private val playlistVersionLock = Any()
    private var observedQueueSignature: List<String>? = null
    private var playlistVersion = 1
    private val startedAtMillis = System.currentTimeMillis()

    private fun songIdOf(entryId: QueueEntryId): Int = entryId.value.hashCode() and 0x7fffffff

    fun observeSubsystems(): SubsystemSnapshot {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        return SubsystemSnapshot(playlistVersionOf(state), playerTokenOf(state))
    }

    private fun playerTokenOf(state: PlaybackSessionAggregate?): String =
        listOf(
            state?.playbackState?.name ?: "NONE",
            state?.currentEntryId?.value ?: "",
            state?.lastKnownPosition?.toMillis()?.toString() ?: "",
            state?.lastKnownAt?.toString() ?: "",
        ).joinToString("|")

    private fun playlistVersionOf(state: PlaybackSessionAggregate?): Int {
        val signature = state?.queue?.map { it.id.value } ?: emptyList()
        synchronized(playlistVersionLock) {
            if (signature != observedQueueSignature) {
                if (observedQueueSignature != null) playlistVersion++
                observedQueueSignature = signature
            }
            return playlistVersion
        }
    }

    fun handle(
        line: String,
        commandListIndex: Int = 0,
    ): String {
        val response = dispatch(line)
        return if (commandListIndex > 0 && response.startsWith("ACK [")) {
            response.replaceFirst("@0]", "@$commandListIndex]")
        } else {
            response
        }
    }

    private fun dispatch(line: String): String {
        val parts = parseLine(line)
        val cmd = parts.firstOrNull()?.lowercase() ?: return ack(5, "", "empty command")
        val args = parts.drop(1)
        return when (cmd) {
            "ping" -> ok()
            "status" -> status()
            "currentsong" -> currentSong()
            "play" -> playPos(args.firstOrNull()?.toIntOrNull())
            "playid" -> playId(args.firstOrNull()?.toIntOrNull())
            "pause" -> pause(args.firstOrNull())
            "stop" -> stop()
            "next" -> next()
            "previous" -> previous()
            "playlistinfo" -> playlistInfo()
            "plchanges" -> plChanges(args.firstOrNull()?.toIntOrNull())
            "add" -> add(args.firstOrNull())
            "clear" -> clear()
            "delete" -> delete(args.firstOrNull())
            "deleteid" -> deleteId(args.firstOrNull()?.toIntOrNull())
            "move" -> move(args)
            "moveid" -> moveId(args)
            "seek" -> seekToPosition(args)
            "seekid" -> seekToId(args)
            "seekcur" -> seekCurrent(args.firstOrNull())
            "listplaylists" -> listPlaylists()
            "listplaylistinfo" -> listPlaylistInfo(args.firstOrNull())
            "load" -> loadStoredPlaylist(args.firstOrNull())
            "save" -> saveStoredPlaylist(args.firstOrNull())
            "rm" -> removeStoredPlaylist(args.firstOrNull())
            "playlistadd" -> playlistAdd(args)
            "playlistdelete" -> playlistDelete(args)
            "count" -> count(args)
            "stats" -> stats()
            "lsinfo" -> lsInfo(args.firstOrNull())
            "search" -> search(args)
            "find" -> search(args)
            "list" -> list(args)
            // The playback core owns volume and play-order modes; these MPD mutations are
            // not honoured, so ACK them rather than reporting a false OK that status()
            // would then contradict (repeat:0 random:0 volume:100).
            "setvol", "getvol", "repeat", "random", "single", "consume" ->
                ack(5, cmd, "command not supported by this server")
            "idle" -> ok()
            "noidle" -> ok()
            "close" -> ""
            "outputs" -> "outputid: 0\noutputname: Yaytsa\noutputenabled: 1\nplugin: httpd\nOK\n"
            "decoders" -> ok()
            "tagtypes" ->
                "tagtype: Artist\ntagtype: AlbumArtist\ntagtype: Album\ntagtype: Title\n" +
                    "tagtype: Track\ntagtype: Genre\ntagtype: Date\nOK\n"
            "commands" ->
                listOf(
                    "ping",
                    "status",
                    "currentsong",
                    "play",
                    "playid",
                    "pause",
                    "stop",
                    "next",
                    "previous",
                    "playlistinfo",
                    "plchanges",
                    "add",
                    "clear",
                    "delete",
                    "deleteid",
                    "move",
                    "moveid",
                    "seek",
                    "seekid",
                    "seekcur",
                    "listplaylists",
                    "listplaylistinfo",
                    "load",
                    "save",
                    "rm",
                    "playlistadd",
                    "playlistdelete",
                    "count",
                    "stats",
                    "lsinfo",
                    "search",
                    "find",
                    "list",
                    "idle",
                    "close",
                    "outputs",
                    "tagtypes",
                    "commands",
                ).joinToString("\n") {
                    "command: $it"
                } +
                    "\nOK\n"
            "command_list_begin", "command_list_ok_begin", "command_list_end" -> ok()
            else -> ack(5, cmd, "unknown command")
        }
    }

    private fun status(): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        return buildString {
            appendLine("volume: 100")
            appendLine("repeat: 0")
            appendLine("random: 0")
            appendLine("single: 0")
            appendLine("consume: 0")
            appendLine("playlist: ${playlistVersionOf(state)}")
            appendLine("playlistlength: ${state?.queue?.size ?: 0}")
            appendLine(
                "state: ${when (state?.playbackState?.name) {
                    "PLAYING" -> "play"
                    "PAUSED" -> "pause"
                    else -> "stop"
                }}",
            )
            val currentId = state?.currentEntryId
            if (currentId != null) {
                val idx = state.queue.indexOfFirst { it.id == currentId }
                if (idx >= 0) {
                    appendLine("song: $idx")
                    appendLine("songid: ${songIdOf(currentId)}")
                }
                val durationMs =
                    state.queue
                        .find { it.id == currentId }
                        ?.trackId
                        ?.let { libraryQueries.getTrack(EntityId(it.value))?.durationMs }
                val now = ctxFactory.create(MPD_USER, AggregateVersion.INITIAL).requestTime
                var elapsed = state.computePosition(now)
                if (durationMs != null && elapsed.toMillis() > durationMs) {
                    elapsed = Duration.ofMillis(durationMs)
                }
                appendLine("elapsed: ${String.format(Locale.ROOT, "%.3f", elapsed.toMillis() / 1000.0)}")
                if (durationMs != null) {
                    appendLine("duration: ${String.format(Locale.ROOT, "%.3f", durationMs / 1000.0)}")
                    appendLine("time: ${elapsed.toMillis() / 1000}:${durationMs / 1000}")
                } else {
                    appendLine("time: ${elapsed.toMillis() / 1000}:0")
                }
            }
            appendLine("OK")
        }
    }

    private fun currentSong(): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION) ?: return ok()
        val entryId = state.currentEntryId ?: return ok()
        val entry = state.queue.find { it.id == entryId } ?: return ok()
        val track = libraryQueries.getTrack(EntityId(entry.trackId.value))
        val sb = StringBuilder()
        if (track != null) {
            sb.append(trackFormatter.block(track))
        } else {
            sb.appendLine("file: ${entry.trackId.value}")
        }
        val idx = state.queue.indexOfFirst { it.id == entryId }
        sb.appendLine("Pos: $idx")
        sb.appendLine("Id: ${songIdOf(entryId)}")
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun playlistBody(state: PlaybackSessionAggregate): String {
        val tracksById =
            libraryQueries
                .getTracksByIds(state.queue.map { EntityId(it.trackId.value) })
                .associateBy { it.id.value }
        val names = trackFormatter.namesFor(tracksById.values.toList())
        val sb = StringBuilder()
        state.queue.forEachIndexed { idx, entry ->
            val track = tracksById[entry.trackId.value]
            if (track != null) {
                sb.append(trackFormatter.block(track, names))
            } else {
                sb.appendLine("file: ${entry.trackId.value}")
            }
            sb.appendLine("Pos: $idx")
            sb.appendLine("Id: ${songIdOf(entry.id)}")
        }
        return sb.toString()
    }

    private fun playlistInfo(): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION) ?: return ok()
        return playlistBody(state) + "OK\n"
    }

    private fun plChanges(sinceVersion: Int?): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION) ?: return ok()
        val current = playlistVersionOf(state)
        if (sinceVersion != null && sinceVersion >= current) return ok()
        return playlistBody(state) + "OK\n"
    }

    private fun add(uri: String?): String {
        if (uri == null) return ack(2, "add", "missing uri")
        val tracks = resolveTracks(uri.trim('/'))
        if (tracks.isEmpty()) return ack(50, "add", "No such song")
        val entries = tracks.map { QueueEntry(QueueEntryId(UUID.randomUUID().toString()), TrackId(it.id.value)) }
        return executeCommand("add") { AddToQueue(MPD_SESSION, MPD_DEVICE, entries) }
    }

    private fun clear(): String = executeCommand("clear") { ClearQueue(MPD_SESSION, MPD_DEVICE) }

    private fun resolveTracks(uri: String): List<Track> {
        if (runCatching { UUID.fromString(uri) }.isSuccess) {
            libraryQueries.getTrack(EntityId(uri))?.let { return listOf(it) }
        }
        val segments = uri.split("/")
        val artist = libraryQueries.findArtistByName(segments[0]) ?: return emptyList()
        val albums = libraryQueries.browseAlbumsByArtist(artist.id)
        return when (segments.size) {
            1 -> albums.flatMap { libraryQueries.browseTracksByAlbum(it.id) }
            2 ->
                albums
                    .firstOrNull { it.name == segments[1] }
                    ?.let { libraryQueries.browseTracksByAlbum(it.id) }
                    .orEmpty()
            else -> emptyList()
        }
    }

    private fun allArtists(): List<Artist> {
        val result = mutableListOf<Artist>()
        var offset = 0
        while (true) {
            val page = libraryQueries.browseArtists(BROWSE_PAGE_SIZE, offset)
            result += page
            if (page.size < BROWSE_PAGE_SIZE) return result
            offset += BROWSE_PAGE_SIZE
        }
    }

    private fun allAlbums(): List<Album> {
        val result = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val page = libraryQueries.browseAlbums(BROWSE_PAGE_SIZE, offset)
            result += page
            if (page.size < BROWSE_PAGE_SIZE) return result
            offset += BROWSE_PAGE_SIZE
        }
    }

    private fun lsInfo(uri: String?): String {
        val path = uri?.trim('/')?.takeIf { it.isNotEmpty() }
        val sb = StringBuilder()
        if (path == null) {
            allArtists().forEach { sb.appendLine("directory: ${it.name}") }
            sb.appendLine("OK")
            return sb.toString()
        }
        val segments = path.split("/")
        val artist = libraryQueries.findArtistByName(segments[0]) ?: return ack(50, "lsinfo", "No such directory")
        when (segments.size) {
            1 ->
                libraryQueries.browseAlbumsByArtist(artist.id).forEach {
                    sb.appendLine("directory: ${artist.name}/${it.name}")
                }
            2 -> {
                val album =
                    libraryQueries
                        .browseAlbumsByArtist(artist.id)
                        .firstOrNull { it.name == segments[1] }
                        ?: return ack(50, "lsinfo", "No such directory")
                val tracks = libraryQueries.browseTracksByAlbum(album.id)
                val names = trackFormatter.namesFor(tracks)
                tracks.forEach { sb.append(trackFormatter.block(it, names)) }
            }
            else -> return ack(50, "lsinfo", "No such directory")
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun search(args: List<String>): String {
        val query = args.lastOrNull()?.removeSurrounding("\"") ?: return ok()
        val results = libraryQueries.searchText(query, 50, 0)
        val names = trackFormatter.namesFor(results.tracks)
        val sb = StringBuilder()
        results.tracks.forEach { track ->
            sb.append(trackFormatter.block(track, names))
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun list(args: List<String>): String {
        val tag = args.firstOrNull()?.lowercase() ?: return ok()
        val sb = StringBuilder()
        when (tag) {
            "artist" -> allArtists().forEach { sb.appendLine("Artist: ${it.name}") }
            "albumartist" -> allArtists().forEach { sb.appendLine("AlbumArtist: ${it.name}") }
            "album" -> allAlbums().forEach { sb.appendLine("Album: ${it.name}") }
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun playPos(pos: Int?): String {
        if (pos == null) return executeCommand("play") { Play(MPD_SESSION, MPD_DEVICE, null) }
        val queue = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)?.queue ?: emptyList()
        if (pos < 0 || pos >= queue.size) return ack(2, "play", "Bad song index")
        val entryId = queue[pos].id
        return executeCommand("play") { Play(MPD_SESSION, MPD_DEVICE, entryId) }
    }

    private fun playId(songId: Int?): String {
        if (songId == null) return executeCommand("playid") { Play(MPD_SESSION, MPD_DEVICE, null) }
        val queue = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)?.queue ?: emptyList()
        val entryId = queue.firstOrNull { songIdOf(it.id) == songId }?.id ?: return ack(50, "playid", "No such song")
        return executeCommand("playid") { Play(MPD_SESSION, MPD_DEVICE, entryId) }
    }

    private fun pause(arg: String?): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        val isPlaying = state?.playbackState == PlaybackState.PLAYING
        val shouldPause =
            when (arg) {
                "1" -> true
                "0" -> false
                null -> isPlaying
                else -> return ack(2, "pause", "Integer expected: $arg")
            }
        return if (shouldPause) {
            if (isPlaying) executeCommand("pause") { Pause(MPD_SESSION, MPD_DEVICE) } else ok()
        } else {
            executeCommand("pause") { Play(MPD_SESSION, MPD_DEVICE, null) }
        }
    }

    private fun stop(): String = executeCommand("stop") { Stop(MPD_SESSION, MPD_DEVICE) }

    private fun next(): String = executeCommand("next") { SkipNext(MPD_SESSION, MPD_DEVICE) }

    private fun previous(): String = executeCommand("previous") { SkipPrevious(MPD_SESSION, MPD_DEVICE) }

    private fun currentQueue(): List<QueueEntry> = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)?.queue ?: emptyList()

    private fun parseRange(
        arg: String,
        queueSize: Int,
    ): IntRange? {
        if (":" in arg) {
            val start = arg.substringBefore(':').toIntOrNull() ?: return null
            val endRaw = arg.substringAfter(':')
            val end = if (endRaw.isEmpty()) queueSize else endRaw.toIntOrNull() ?: return null
            return if (start < 0 || end > queueSize || start >= end) null else start until end
        }
        val pos = arg.toIntOrNull() ?: return null
        return if (pos < 0 || pos >= queueSize) null else pos..pos
    }

    private fun parseSeconds(raw: String?): Duration? = raw?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Duration.ofMillis((it * 1000).toLong()) }

    private fun delete(arg: String?): String {
        if (arg == null) return ack(2, "delete", "missing position")
        val queue = currentQueue()
        val range = parseRange(arg, queue.size) ?: return ack(2, "delete", "Bad song index")
        return removeEntries(range.map { queue[it].id }, "delete")
    }

    private fun deleteId(songId: Int?): String {
        if (songId == null) return ack(2, "deleteid", "Integer expected")
        val entry = currentQueue().firstOrNull { songIdOf(it.id) == songId } ?: return ack(50, "deleteid", "No such song")
        return removeEntries(listOf(entry.id), "deleteid")
    }

    private fun removeEntries(
        entryIds: List<QueueEntryId>,
        commandName: String,
    ): String {
        entryIds.forEach { entryId ->
            val response = executeCommand(commandName) { RemoveFromQueue(MPD_SESSION, MPD_DEVICE, entryId) }
            if (response != ok()) return response
        }
        return ok()
    }

    private fun move(args: List<String>): String {
        val to = args.getOrNull(1)?.toIntOrNull() ?: return ack(2, "move", "Integer expected")
        val queue = currentQueue()
        val range = args.getOrNull(0)?.let { parseRange(it, queue.size) } ?: return ack(2, "move", "Bad song index")
        val entryIds = range.map { queue[it].id }
        if (to < 0 || to + entryIds.size > queue.size) return ack(2, "move", "Bad song index")
        return moveEntries(entryIds, range.first, to, "move")
    }

    private fun moveId(args: List<String>): String {
        val songId = args.getOrNull(0)?.toIntOrNull() ?: return ack(2, "moveid", "Integer expected")
        val to = args.getOrNull(1)?.toIntOrNull() ?: return ack(2, "moveid", "Integer expected")
        val queue = currentQueue()
        val idx = queue.indexOfFirst { songIdOf(it.id) == songId }
        if (idx == -1) return ack(50, "moveid", "No such song")
        if (to < 0 || to >= queue.size) return ack(2, "moveid", "Bad song index")
        return moveEntries(listOf(queue[idx].id), idx, to, "moveid")
    }

    private fun moveEntries(
        entryIds: List<QueueEntryId>,
        fromIndex: Int,
        toIndex: Int,
        commandName: String,
    ): String {
        val order = if (toIndex > fromIndex) entryIds.indices.reversed() else entryIds.indices
        for (offset in order) {
            val response = executeCommand(commandName) { MoveQueueEntry(MPD_SESSION, MPD_DEVICE, entryIds[offset], toIndex + offset) }
            if (response != ok()) return response
        }
        return ok()
    }

    private fun seekToPosition(args: List<String>): String {
        val pos = args.getOrNull(0)?.toIntOrNull() ?: return ack(2, "seek", "Integer expected")
        val time = parseSeconds(args.getOrNull(1)) ?: return ack(2, "seek", "Number expected")
        if (time.isNegative) return ack(2, "seek", "Negative seek")
        val queue = currentQueue()
        if (pos < 0 || pos >= queue.size) return ack(2, "seek", "Bad song index")
        return playThenSeek(queue[pos].id, time, "seek")
    }

    private fun seekToId(args: List<String>): String {
        val songId = args.getOrNull(0)?.toIntOrNull() ?: return ack(2, "seekid", "Integer expected")
        val time = parseSeconds(args.getOrNull(1)) ?: return ack(2, "seekid", "Number expected")
        if (time.isNegative) return ack(2, "seekid", "Negative seek")
        val entry = currentQueue().firstOrNull { songIdOf(it.id) == songId } ?: return ack(50, "seekid", "No such song")
        return playThenSeek(entry.id, time, "seekid")
    }

    private fun seekCurrent(arg: String?): String {
        if (arg == null) return ack(2, "seekcur", "Number expected")
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        if (state?.currentEntryId == null) return ack(55, "seekcur", "Not playing")
        val delta = parseSeconds(arg) ?: return ack(2, "seekcur", "Number expected")
        val target =
            when {
                arg.startsWith("+") || arg.startsWith("-") -> {
                    val now = ctxFactory.create(MPD_USER, AggregateVersion.INITIAL).requestTime
                    val computed = state.computePosition(now) + delta
                    if (computed.isNegative) Duration.ZERO else computed
                }
                delta.isNegative -> return ack(2, "seekcur", "Negative seek")
                else -> delta
            }
        return executeCommand("seekcur") { Seek(MPD_SESSION, MPD_DEVICE, target) }
    }

    private fun playThenSeek(
        entryId: QueueEntryId,
        time: Duration,
        commandName: String,
    ): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        if (state?.currentEntryId != entryId || state.playbackState == PlaybackState.STOPPED) {
            val response = executeCommand(commandName) { Play(MPD_SESSION, MPD_DEVICE, entryId) }
            if (response != ok()) return response
        }
        return executeCommand(commandName) { Seek(MPD_SESSION, MPD_DEVICE, time) }
    }

    private fun findStoredPlaylist(name: String): PlaylistAggregate? =
        playlistQueries
            .findByOwner(MPD_USER)
            .filter { it.name == name }
            .maxByOrNull { it.updatedAt }

    private fun listPlaylists(): String =
        buildString {
            playlistQueries.findByOwner(MPD_USER).forEach {
                appendLine("playlist: ${it.name}")
                appendLine("Last-Modified: ${it.updatedAt.truncatedTo(ChronoUnit.SECONDS)}")
            }
            appendLine("OK")
        }

    private fun listPlaylistInfo(name: String?): String {
        if (name == null) return ack(2, "listplaylistinfo", "missing name")
        val playlist = findStoredPlaylist(name) ?: return ack(50, "listplaylistinfo", "No such playlist")
        val tracksById =
            libraryQueries
                .getTracksByIds(playlist.tracks.map { EntityId(it.trackId.value) })
                .associateBy { it.id.value }
        val names = trackFormatter.namesFor(tracksById.values.toList())
        return buildString {
            playlist.tracks.forEach { entry ->
                val track = tracksById[entry.trackId.value]
                if (track != null) {
                    append(trackFormatter.block(track, names))
                } else {
                    appendLine("file: ${entry.trackId.value}")
                }
            }
            appendLine("OK")
        }
    }

    private fun loadStoredPlaylist(name: String?): String {
        if (name == null) return ack(2, "load", "missing name")
        val playlist = findStoredPlaylist(name) ?: return ack(50, "load", "No such playlist")
        if (playlist.tracks.isEmpty()) return ok()
        val entries = playlist.tracks.map { QueueEntry(QueueEntryId(UUID.randomUUID().toString()), it.trackId) }
        return executeCommand("load") { AddToQueue(MPD_SESSION, MPD_DEVICE, entries) }
    }

    private fun saveStoredPlaylist(name: String?): String {
        if (name == null) return ack(2, "save", "missing name")
        if (findStoredPlaylist(name) != null) return ack(56, "save", "Playlist already exists")
        val queue = currentQueue()
        return when (val created = createStoredPlaylist(name)) {
            is CommandResult.Failed -> failureTranslator.translate(created.failure, "save")
            is CommandResult.Success ->
                if (queue.isEmpty()) {
                    ok()
                } else {
                    executePlaylistCommand("save", created.newVersion) {
                        AddTracksToPlaylist(created.value.id, queue.map { entry -> entry.trackId }, it)
                    }
                }
        }
    }

    private fun removeStoredPlaylist(name: String?): String {
        if (name == null) return ack(2, "rm", "missing name")
        val playlist = findStoredPlaylist(name) ?: return ack(50, "rm", "No such playlist")
        return executePlaylistCommand("rm", playlist.version) { DeletePlaylist(playlist.id) }
    }

    private fun playlistAdd(args: List<String>): String {
        val name = args.getOrNull(0) ?: return ack(2, "playlistadd", "missing name")
        val uri = args.getOrNull(1) ?: return ack(2, "playlistadd", "missing uri")
        val tracks = resolveTracks(uri.trim('/'))
        if (tracks.isEmpty()) return ack(50, "playlistadd", "No such song")
        val playlist =
            findStoredPlaylist(name) ?: when (val created = createStoredPlaylist(name)) {
                is CommandResult.Failed -> return failureTranslator.translate(created.failure, "playlistadd")
                is CommandResult.Success -> created.value
            }
        return executePlaylistCommand("playlistadd", playlist.version) {
            AddTracksToPlaylist(playlist.id, tracks.map { track -> TrackId(track.id.value) }, it)
        }
    }

    private fun playlistDelete(args: List<String>): String {
        val name = args.getOrNull(0) ?: return ack(2, "playlistdelete", "missing name")
        val pos = args.getOrNull(1)?.toIntOrNull() ?: return ack(2, "playlistdelete", "Integer expected")
        val playlist = findStoredPlaylist(name) ?: return ack(50, "playlistdelete", "No such playlist")
        return executePlaylistCommand("playlistdelete", playlist.version) {
            RemovePlaylistEntriesByPosition(playlist.id, listOf(pos))
        }
    }

    private fun createStoredPlaylist(name: String): CommandResult<PlaylistAggregate> {
        val ctx = ctxFactory.create(MPD_USER, AggregateVersion.INITIAL)
        return playlistUseCases.execute(
            CreatePlaylist(PlaylistId(UUID.randomUUID().toString()), MPD_USER, name, null, false, ctx.requestTime),
            ctx,
        )
    }

    private fun executePlaylistCommand(
        commandName: String,
        expectedVersion: AggregateVersion,
        factory: (Instant) -> PlaylistCommand,
    ): String {
        val ctx = ctxFactory.create(MPD_USER, expectedVersion)
        return when (val result = playlistUseCases.execute(factory(ctx.requestTime), ctx)) {
            is CommandResult.Success -> ok()
            is CommandResult.Failed -> failureTranslator.translate(result.failure, commandName)
        }
    }

    private fun count(args: List<String>): String {
        val tag = args.getOrNull(0)?.lowercase() ?: return ack(2, "count", "too few arguments")
        val needle = args.getOrNull(1) ?: return ack(2, "count", "too few arguments")
        val tracks =
            when (tag) {
                "artist", "albumartist" -> tracksByArtistName(needle)
                "album" -> allAlbums().filter { it.name == needle }.flatMap { libraryQueries.browseTracksByAlbum(it.id) }
                else -> return ack(2, "count", "Unsupported tag: $tag")
            }
        return buildString {
            appendLine("songs: ${tracks.size}")
            appendLine("playtime: ${tracks.sumOf { it.durationMs ?: 0L } / 1000}")
            appendLine("OK")
        }
    }

    private fun tracksByArtistName(name: String): List<Track> {
        val artist = libraryQueries.findArtistByName(name) ?: return emptyList()
        val result = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val page = libraryQueries.browseTracksByArtist(artist.id, BROWSE_PAGE_SIZE, offset)
            result += page
            if (page.size < BROWSE_PAGE_SIZE) return result
            offset += BROWSE_PAGE_SIZE
        }
    }

    private fun stats(): String =
        buildString {
            appendLine("artists: ${libraryQueries.countArtists()}")
            appendLine("albums: ${libraryQueries.countAlbums()}")
            appendLine("songs: ${libraryQueries.countTracks()}")
            appendLine("uptime: ${(System.currentTimeMillis() - startedAtMillis) / 1000}")
            appendLine("db_playtime: ${libraryQueries.sumTrackDurationsMs() / 1000}")
            appendLine("OK")
        }

    private fun ensureLease(): CommandResult.Failed? {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        val now = ctxFactory.create(MPD_USER, AggregateVersion.INITIAL).requestTime
        val lease = state?.lease
        if (lease != null && lease.owner == MPD_DEVICE && now < lease.expiresAt) return null
        val version = state?.version ?: AggregateVersion.INITIAL
        val ctx = ctxFactory.create(MPD_USER, version)
        return when (
            val result =
                playbackUseCases.execute(AcquireLease(MPD_SESSION, MPD_DEVICE, MPD_LEASE_DURATION), ctx)
        ) {
            is CommandResult.Success -> null
            is CommandResult.Failed -> result
        }
    }

    private fun executeCommand(
        commandName: String,
        factory: () -> dev.yaytsa.domain.playback.PlaybackCommand,
    ): String {
        ensureLease()?.let { return failureTranslator.translate(it.failure, commandName) }
        val currentState = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        val version = currentState?.version ?: AggregateVersion.INITIAL
        val ctx = ctxFactory.create(MPD_USER, version)
        return when (val result = playbackUseCases.execute(factory(), ctx)) {
            is CommandResult.Success -> ok()
            is CommandResult.Failed -> failureTranslator.translate(result.failure, commandName)
        }
    }

    private fun ok() = "OK\n"

    private fun ack(
        code: Int,
        cmd: String,
        msg: String,
    ) = "ACK [$code@0] {$cmd} $msg\n"

    private fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            when {
                line[i].isWhitespace() -> i++
                line[i] == '"' -> {
                    val end = line.indexOf('"', i + 1)
                    if (end == -1) {
                        result.add(line.substring(i + 1))
                        break
                    }
                    result.add(line.substring(i + 1, end))
                    i = end + 1
                }
                else -> {
                    val end = line.indexOf(' ', i)
                    if (end == -1) {
                        result.add(line.substring(i))
                        break
                    }
                    result.add(line.substring(i, end))
                    i = end + 1
                }
            }
        }
        return result
    }
}
