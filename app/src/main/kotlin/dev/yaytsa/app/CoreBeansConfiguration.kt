package dev.yaytsa.app

import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.adaptive.port.PlaybackSignalWritePort
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.shared.EntityId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class CoreBeansConfiguration {
    @Bean
    fun protocolCapabilitiesRegistry(capabilities: List<ProtocolCapabilities>): ProtocolCapabilitiesRegistry = ProtocolCapabilitiesRegistry(capabilities)

    @Bean
    fun adaptiveUseCases(
        sessionRepo: AdaptiveSessionRepository,
        idempotencyStore: IdempotencyStore,
        capabilities: ProtocolCapabilitiesRegistry,
        txExecutor: TransactionalCommandExecutor,
        outbox: OutboxPort,
        libraryQuery: LibraryQueryPort,
        signalWriter: PlaybackSignalWritePort,
    ): AdaptiveUseCases =
        AdaptiveUseCases(
            sessionRepo = sessionRepo,
            idempotencyStore = idempotencyStore,
            capabilities = capabilities,
            txExecutor = txExecutor,
            outbox = outbox,
            trackValidator = { trackIds -> libraryQuery.trackIdsExist(trackIds) },
            signalWriter = signalWriter,
        )

    @Bean
    fun authUseCases(
        userRepo: UserRepository,
        idempotencyStore: IdempotencyStore,
        capabilities: ProtocolCapabilitiesRegistry,
        txExecutor: TransactionalCommandExecutor,
        outbox: OutboxPort,
    ): AuthUseCases = AuthUseCases(userRepo, idempotencyStore, capabilities, txExecutor, outbox)

    @Bean
    fun playbackUseCases(
        sessionRepo: PlaybackSessionRepository,
        idempotencyStore: IdempotencyStore,
        capabilities: ProtocolCapabilitiesRegistry,
        txExecutor: TransactionalCommandExecutor,
        outbox: OutboxPort,
        libraryQuery: LibraryQueryPort,
    ): PlaybackUseCases =
        PlaybackUseCases(
            sessionRepo = sessionRepo,
            idempotencyStore = idempotencyStore,
            capabilities = capabilities,
            txExecutor = txExecutor,
            outbox = outbox,
            trackValidator = { trackIds -> libraryQuery.trackIdsExist(trackIds) },
            trackDurationLoader = { trackId ->
                libraryQuery.getTrack(EntityId(trackId.value))?.durationMs?.let { Duration.ofMillis(it) }
            },
        )

    @Bean
    fun scrobbleService(playHistoryWriter: PlayHistoryWritePort): ScrobbleService = ScrobbleService(playHistoryWriter)

    @Bean
    fun playlistUseCases(
        playlistRepo: PlaylistRepository,
        idempotencyStore: IdempotencyStore,
        capabilities: ProtocolCapabilitiesRegistry,
        txExecutor: TransactionalCommandExecutor,
        outbox: OutboxPort,
        libraryQuery: LibraryQueryPort,
    ): PlaylistUseCases =
        PlaylistUseCases(
            playlistRepo = playlistRepo,
            idempotencyStore = idempotencyStore,
            capabilities = capabilities,
            txExecutor = txExecutor,
            outbox = outbox,
            trackValidator = { trackIds -> libraryQuery.trackIdsExist(trackIds) },
        )

    @Bean
    fun authQueries(userRepo: UserRepository): AuthQueries = AuthQueries(userRepo)

    @Bean
    fun playbackQueries(sessionRepo: PlaybackSessionRepository): PlaybackQueries = PlaybackQueries(sessionRepo)

    @Bean
    fun playlistQueries(playlistRepo: PlaylistRepository): PlaylistQueries = PlaylistQueries(playlistRepo)

    @Bean
    fun preferencesQueries(prefsRepo: UserPreferencesRepository): PreferencesQueries = PreferencesQueries(prefsRepo)

    @Bean
    fun libraryQueries(libraryQuery: LibraryQueryPort): LibraryQueries = LibraryQueries(libraryQuery)

    @Bean
    fun deviceSessionProjection(): dev.yaytsa.application.playback.DeviceSessionProjection =
        dev.yaytsa.application.playback
            .DeviceSessionProjection()

    @Bean
    fun preferencesUseCases(
        prefsRepo: UserPreferencesRepository,
        idempotencyStore: IdempotencyStore,
        capabilities: ProtocolCapabilitiesRegistry,
        txExecutor: TransactionalCommandExecutor,
        outbox: OutboxPort,
        libraryQuery: LibraryQueryPort,
    ): PreferencesUseCases =
        PreferencesUseCases(
            prefsRepo = prefsRepo,
            idempotencyStore = idempotencyStore,
            capabilities = capabilities,
            txExecutor = txExecutor,
            outbox = outbox,
            trackValidator = { trackIds -> libraryQuery.trackIdsExist(trackIds) },
        )
}
