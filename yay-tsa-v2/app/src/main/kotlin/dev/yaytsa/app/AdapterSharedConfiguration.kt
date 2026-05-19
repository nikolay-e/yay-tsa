package dev.yaytsa.app

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.HttpFailureTranslator
import dev.yaytsa.adaptershared.MpdFailureTranslator
import dev.yaytsa.adaptershared.SubsonicFailureTranslator
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.ProtocolId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AdapterSharedConfiguration {
    @Bean
    fun jellyfinCommandContextFactory(clock: Clock): AdapterCommandContextFactory = AdapterCommandContextFactory(ProtocolId("JELLYFIN"), clock)

    @Bean
    fun subsonicCommandContextFactory(clock: Clock): AdapterCommandContextFactory = AdapterCommandContextFactory(ProtocolId("OPENSUBSONIC"), clock)

    @Bean
    fun mpdCommandContextFactory(clock: Clock): AdapterCommandContextFactory = AdapterCommandContextFactory(ProtocolId("MPD"), clock)

    @Bean
    fun mcpCommandContextFactory(clock: Clock): AdapterCommandContextFactory = AdapterCommandContextFactory(ProtocolId("MCP"), clock)

    @Bean
    fun httpFailureTranslator(): HttpFailureTranslator = HttpFailureTranslator()

    @Bean
    fun subsonicFailureTranslator(): SubsonicFailureTranslator = SubsonicFailureTranslator()

    @Bean
    fun mpdFailureTranslator(): MpdFailureTranslator = MpdFailureTranslator()
}
