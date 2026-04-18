package dev.yaytsa.adaptermpd

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.Stop
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
            Play::class,
            Pause::class,
            Stop::class,
            SkipNext::class,
            SkipPrevious::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
