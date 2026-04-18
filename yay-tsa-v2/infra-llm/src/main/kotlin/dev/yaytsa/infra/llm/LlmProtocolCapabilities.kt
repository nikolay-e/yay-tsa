package dev.yaytsa.infra.llm

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.domain.adaptive.RewriteQueueTail
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class LlmProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("LLM")
    override val supportedCommands: Set<KClass<out Command>> =
        setOf(
            RewriteQueueTail::class,
            RecordPlaybackSignal::class,
        )
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
