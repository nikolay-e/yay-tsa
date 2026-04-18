package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.adaptive.EndListeningSession
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.domain.adaptive.RewriteQueueTail
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.adaptive.UpdateSessionContext
import dev.yaytsa.domain.auth.ActivateUser
import dev.yaytsa.domain.auth.ChangePassword
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.auth.DeactivateUser
import dev.yaytsa.domain.auth.RecordLogin
import dev.yaytsa.domain.auth.RecordTokenUsage
import dev.yaytsa.domain.auth.RevokeApiToken
import dev.yaytsa.domain.auth.UpdateProfile
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.MoveQueueEntry
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.RefreshLease
import dev.yaytsa.domain.playback.ReleaseLease
import dev.yaytsa.domain.playback.RemoveFromQueue
import dev.yaytsa.domain.playback.ReplaceQueue
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.StartPlaybackWithTracks
import dev.yaytsa.domain.playback.Stop
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.RemoveTracksFromPlaylist
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.playlists.ReorderPlaylistTracks
import dev.yaytsa.domain.playlists.SetPlaylistVisibility
import dev.yaytsa.domain.playlists.UpdatePlaylistDescription
import dev.yaytsa.domain.preferences.ReorderFavorites
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class JellyfinProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("JELLYFIN")
    override val supportedCommands: Set<KClass<out Command>> =
        setOf(
            CreateUser::class,
            UpdateProfile::class,
            ChangePassword::class,
            DeactivateUser::class,
            ActivateUser::class,
            RecordLogin::class,
            CreateApiToken::class,
            RevokeApiToken::class,
            RecordTokenUsage::class,
            AcquireLease::class,
            ReleaseLease::class,
            RefreshLease::class,
            AddToQueue::class,
            RemoveFromQueue::class,
            ReplaceQueue::class,
            ClearQueue::class,
            MoveQueueEntry::class,
            Play::class,
            Pause::class,
            Stop::class,
            Seek::class,
            SkipNext::class,
            SkipPrevious::class,
            StartPlaybackWithTracks::class,
            CreatePlaylist::class,
            RenamePlaylist::class,
            UpdatePlaylistDescription::class,
            SetPlaylistVisibility::class,
            DeletePlaylist::class,
            AddTracksToPlaylist::class,
            RemoveTracksFromPlaylist::class,
            ReorderPlaylistTracks::class,
            SetFavorite::class,
            UnsetFavorite::class,
            ReorderFavorites::class,
            UpdatePreferenceContract::class,
            StartListeningSession::class,
            EndListeningSession::class,
            UpdateSessionContext::class,
            RewriteQueueTail::class,
            RecordPlaybackSignal::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
