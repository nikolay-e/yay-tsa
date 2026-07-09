package dev.yaytsa.app

import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {
    // Without strict null checks the Kotlin module lets a JSON null slip into a
    // non-nullable List<String> field; the first element access then dies with a
    // Kotlin intrinsics NPE inside the controller (observed as a 500 on
    // POST /Playlists {"Ids":["a",null]}). With the feature enabled Jackson throws
    // at parse time, which the message-conversion handlers map to a 400.
    // (NewStrictNullChecks needs jackson-module-kotlin 2.19+; catalog pins 2.18.)
    @Bean
    fun kotlinModule(): KotlinModule =
        KotlinModule
            .Builder()
            .enable(KotlinFeature.StrictNullChecks)
            .build()
}
