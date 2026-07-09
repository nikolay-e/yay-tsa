package dev.yaytsa.adaptermcp

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class McpProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("MCP")
    override val supportedCommands: Set<KClass<out Command>> =
        setOf(
            // Mint device tokens during the OAuth authorization-code flow
            CreateApiToken::class,
            // Control playback
            Play::class,
            Pause::class,
            SkipNext::class,
            SkipPrevious::class,
            // Manage the queue
            AddToQueue::class,
            ClearQueue::class,
            // Modify the preference contract
            UpdatePreferenceContract::class,
            // Steer adaptive behavior
            StartListeningSession::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
