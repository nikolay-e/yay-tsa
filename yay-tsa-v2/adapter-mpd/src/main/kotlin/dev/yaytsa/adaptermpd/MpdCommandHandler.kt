package dev.yaytsa.adaptermpd

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.MpdFailureTranslator
import dev.yaytsa.adaptershared.toMpdLines
import dev.yaytsa.adaptershared.toMpdSummaryLines
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.Stop
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale

@Component
class MpdCommandHandler(
    private val playbackQueries: PlaybackQueries,
    private val playbackUseCases: PlaybackUseCases,
    private val libraryQueries: LibraryQueries,
    @Qualifier("mpdCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val failureTranslator: MpdFailureTranslator,
) {
    companion object {
        private val MPD_USER = UserId("mpd-default")
        private val MPD_SESSION = SessionId("mpd-default")
        private val MPD_DEVICE = DeviceId("mpd")
        private val MPD_LEASE_DURATION = Duration.ofHours(6)
    }

    private fun songIdOf(entryId: QueueEntryId): Int = entryId.value.hashCode() and 0x7fffffff

    fun handle(line: String): String {
        val parts = parseLine(line)
        val cmd = parts.firstOrNull()?.lowercase() ?: return ack(5, "", "empty command")
        val args = parts.drop(1)
        return when (cmd) {
            "ping" -> ok()
            "status" -> status()
            "currentsong" -> currentSong()
            "play" -> playPos(args.firstOrNull()?.toIntOrNull())
            "playid" -> playId(args.firstOrNull()?.toIntOrNull())
            "pause" -> pause()
            "stop" -> stop()
            "next" -> next()
            "previous" -> previous()
            "playlistinfo" -> playlistInfo()
            "lsinfo" -> lsInfo(args.firstOrNull())
            "search" -> search(args)
            "find" -> search(args)
            "list" -> list(args)
            // The adaptive/playback core owns the queue and playback modes; these MPD
            // mutations are not honoured, so ACK them rather than reporting a false OK
            // that status() would then contradict (repeat:0 random:0 volume:100).
            "clear", "add", "setvol", "repeat", "random", "single", "consume" ->
                ack(5, cmd, "command not supported by this server")
            "idle" -> "changed: player\nOK\n"
            "noidle" -> ok()
            "close" -> ""
            "outputs" -> "outputid: 0\noutputname: Yaytsa\noutputenabled: 1\nplugin: httpd\nOK\n"
            "decoders" -> ok()
            "tagtypes" -> "tagtype: Artist\ntagtype: Album\ntagtype: Title\ntagtype: Track\ntagtype: Genre\ntagtype: Date\nOK\n"
            "commands" ->
                listOf(
                    "ping",
                    "status",
                    "currentsong",
                    "play",
                    "pause",
                    "stop",
                    "next",
                    "previous",
                    "playlistinfo",
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
        val sb = StringBuilder()
        sb.appendLine("volume: 100")
        sb.appendLine("repeat: 0")
        sb.appendLine("random: 0")
        sb.appendLine("single: 0")
        sb.appendLine("consume: 0")
        sb.appendLine("playlist: 1")
        sb.appendLine("playlistlength: ${state?.queue?.size ?: 0}")
        sb.appendLine(
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
                sb.appendLine("song: $idx")
                sb.appendLine("songid: ${songIdOf(currentId)}")
            }
            sb.appendLine("elapsed: ${String.format(Locale.ROOT, "%.3f", state.lastKnownPosition.toMillis() / 1000.0)}")
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun currentSong(): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION) ?: return ok()
        val entryId = state.currentEntryId ?: return ok()
        val entry = state.queue.find { it.id == entryId } ?: return ok()
        val track = libraryQueries.getTrack(EntityId(entry.trackId.value))
        val sb = StringBuilder()
        if (track != null) {
            sb.append(track.toMpdLines())
        } else {
            sb.appendLine("file: ${entry.trackId.value}")
        }
        val idx = state.queue.indexOfFirst { it.id == entryId }
        sb.appendLine("Pos: $idx")
        sb.appendLine("Id: ${songIdOf(entryId)}")
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun playlistInfo(): String {
        val state = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION) ?: return ok()
        val sb = StringBuilder()
        state.queue.forEachIndexed { idx, entry ->
            val track = libraryQueries.getTrack(EntityId(entry.trackId.value))
            if (track != null) {
                sb.append(track.toMpdSummaryLines())
            } else {
                sb.appendLine("file: ${entry.trackId.value}")
            }
            sb.appendLine("Pos: $idx")
            sb.appendLine("Id: ${songIdOf(entry.id)}")
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun lsInfo(uri: String?): String {
        val artists = libraryQueries.browseArtists(50, 0)
        val sb = StringBuilder()
        artists.forEach {
            sb.appendLine("directory: ${it.name}")
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun search(args: List<String>): String {
        val query = args.lastOrNull()?.removeSurrounding("\"") ?: return ok()
        val results = libraryQueries.searchText(query, 50, 0)
        val sb = StringBuilder()
        results.tracks.forEach { track ->
            sb.append(track.toMpdSummaryLines())
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun list(args: List<String>): String {
        val tag = args.firstOrNull()?.lowercase() ?: return ok()
        val artists = libraryQueries.browseArtists(500, 0)
        val sb = StringBuilder()
        when (tag) {
            "artist", "albumartist" -> artists.forEach { sb.appendLine("Artist: ${it.name}") }
            "album" -> artists.flatMap { libraryQueries.browseAlbumsByArtist(it.id) }.forEach { sb.appendLine("Album: ${it.name}") }
        }
        sb.appendLine("OK")
        return sb.toString()
    }

    private fun playPos(pos: Int?): String {
        if (pos == null) return executeCommand { Play(MPD_SESSION, MPD_DEVICE, null) }
        val queue = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)?.queue ?: emptyList()
        if (pos < 0 || pos >= queue.size) return ack(2, "play", "Bad song index")
        val entryId = queue[pos].id
        return executeCommand { Play(MPD_SESSION, MPD_DEVICE, entryId) }
    }

    private fun playId(songId: Int?): String {
        if (songId == null) return executeCommand { Play(MPD_SESSION, MPD_DEVICE, null) }
        val queue = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)?.queue ?: emptyList()
        val entryId = queue.firstOrNull { songIdOf(it.id) == songId }?.id ?: return ack(50, "playid", "No such song")
        return executeCommand { Play(MPD_SESSION, MPD_DEVICE, entryId) }
    }

    private fun pause(): String = executeCommand { Pause(MPD_SESSION, MPD_DEVICE) }

    private fun stop(): String = executeCommand { Stop(MPD_SESSION, MPD_DEVICE) }

    private fun next(): String = executeCommand { SkipNext(MPD_SESSION, MPD_DEVICE) }

    private fun previous(): String = executeCommand { SkipPrevious(MPD_SESSION, MPD_DEVICE) }

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

    private fun executeCommand(factory: () -> dev.yaytsa.domain.playback.PlaybackCommand): String {
        ensureLease()?.let { return failureTranslator.translate(it.failure, "command") }
        val currentState = playbackQueries.getPlaybackState(MPD_USER, MPD_SESSION)
        val version = currentState?.version ?: AggregateVersion.INITIAL
        val ctx = ctxFactory.create(MPD_USER, version)
        return when (val result = playbackUseCases.execute(factory(), ctx)) {
            is CommandResult.Success -> ok()
            is CommandResult.Failed -> failureTranslator.translate(result.failure, "command")
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
