package dev.yaytsa.application.shared

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.Query
import kotlin.reflect.KClass

interface ProtocolCapabilities {
    val protocol: ProtocolId
    val supportedCommands: Set<KClass<out Command>>
    val supportedQueries: Set<KClass<out Query>>
}

class ProtocolCapabilitiesRegistry(
    capabilities: List<ProtocolCapabilities>,
) {
    private val byProtocol: Map<ProtocolId, ProtocolCapabilities> =
        capabilities.associateBy { it.protocol }

    fun isCommandSupported(
        protocolId: ProtocolId,
        commandClass: KClass<out Command>,
    ): Boolean = byProtocol[protocolId]?.supportedCommands?.contains(commandClass) ?: false

    fun isQuerySupported(
        protocolId: ProtocolId,
        queryClass: KClass<out Query>,
    ): Boolean = byProtocol[protocolId]?.supportedQueries?.contains(queryClass) ?: false
}
