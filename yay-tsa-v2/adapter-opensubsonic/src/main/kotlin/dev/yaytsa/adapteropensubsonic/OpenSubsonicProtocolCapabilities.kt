package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.domain.playlists.RemoveTracksFromPlaylist
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.playlists.SetPlaylistVisibility
import dev.yaytsa.domain.playlists.UpdatePlaylistDescription
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class OpenSubsonicProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("OPENSUBSONIC")
    override val supportedCommands: Set<KClass<out Command>> =
        setOf(
            SetFavorite::class,
            UnsetFavorite::class,
            CreatePlaylist::class,
            DeletePlaylist::class,
            AddTracksToPlaylist::class,
            RemoveTracksFromPlaylist::class,
            RemovePlaylistEntriesByPosition::class,
            RenamePlaylist::class,
            SetPlaylistVisibility::class,
            UpdatePlaylistDescription::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
