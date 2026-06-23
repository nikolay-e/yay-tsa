package dev.yaytsa.app

import dev.yaytsa.application.shared.ProtocolCapabilities
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class BootstrapProtocolCapabilities : ProtocolCapabilities {
    override val protocol = ProtocolId("BOOTSTRAP")
    override val supportedCommands: Set<KClass<out Command>> = setOf(CreateUser::class)
    override val supportedQueries: Set<KClass<out Query>> = emptySet()
}
