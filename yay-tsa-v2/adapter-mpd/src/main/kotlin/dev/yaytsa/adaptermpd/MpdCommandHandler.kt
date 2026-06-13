package dev.yaytsa.adaptermpd

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.MpdFailureTranslator
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.Stop
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale
import java.util.UUID

@Component
class MpdCommandHandler(
    private val playbackQueries: PlaybackQueries,
    private val playbackUseCases: PlaybackUseCases,
    private val libraryQueries: LibraryQueries,
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
        private const val ARTIST_PAGE_SIZE = 200
    }

    data class SubsystemSnapshot(
        val playlistVersion: Int,
        val playerToken: String,
    )

    private val playlistVersionLock = Any()
    private var observedQueueSignature: List<String>? = null
    private var playlistVersion = 1

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
            "lsinfo" -> lsInfo(args.firstOrNull())
            "search" -> search(args)
            "find" -> search(args)
            "list" -> list(args)
            // The playback core owns volume and play-order modes; these MPD mutations are
            // not honoured, so ACK them rather than reporting a false OK that status()
            // would then contradict (repeat:0 random:0 volume:100).
            "setvol", "repeat", "random", "single", "consume" ->
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
        val artist = findArtistByName(segments[0]) ?: return emptyList()
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
            val page = libraryQueries.browseArtists(ARTIST_PAGE_SIZE, offset)
            result += page
            if (page.size < ARTIST_PAGE_SIZE) return result
            offset += ARTIST_PAGE_SIZE
        }
    }

    private fun findArtistByName(name: String): Artist? = allArtists().firstOrNull { it.name == name }

    private fun lsInfo(uri: String?): String {
        val path = uri?.trim('/')?.takeIf { it.isNotEmpty() }
        val sb = StringBuilder()
        if (path == null) {
            allArtists().forEach { sb.appendLine("directory: ${it.name}") }
            sb.appendLine("OK")
            return sb.toString()
        }
        val segments = path.split("/")
        val artist = findArtistByName(segments[0]) ?: return ack(50, "lsinfo", "No such directory")
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
        val artists = allArtists()
        val sb = StringBuilder()
        when (tag) {
            "artist" -> artists.forEach { sb.appendLine("Artist: ${it.name}") }
            "albumartist" -> artists.forEach { sb.appendLine("AlbumArtist: ${it.name}") }
            "album" ->
                artists
                    .flatMap { libraryQueries.browseAlbumsByArtist(it.id) }
                    .forEach { sb.appendLine("Album: ${it.name}") }
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
