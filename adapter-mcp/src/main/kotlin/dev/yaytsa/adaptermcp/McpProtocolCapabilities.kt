package dev.yaytsa.adaptermcp

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
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
            Play::class,
            Pause::class,
            SkipNext::class,
            SkipPrevious::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
