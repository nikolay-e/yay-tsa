package dev.yaytsa.adaptermpd

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.MoveQueueEntry
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.RemoveFromQueue
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.Stop
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class MpdProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("MPD")
    override val supportedCommands: Set<KClass<out Command>> =
        setOf(
            AcquireLease::class,
            AddToQueue::class,
            ClearQueue::class,
            Play::class,
            Pause::class,
            Stop::class,
            Seek::class,
            SkipNext::class,
            SkipPrevious::class,
            RemoveFromQueue::class,
            MoveQueueEntry::class,
            CreatePlaylist::class,
            DeletePlaylist::class,
            AddTracksToPlaylist::class,
            RemovePlaylistEntriesByPosition::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
