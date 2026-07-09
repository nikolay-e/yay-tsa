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
    // MismatchedInputException at parse time, which the existing
    // HttpMessageNotReadableException handler maps to a 400.
    @Bean
    fun kotlinModule(): KotlinModule =
        KotlinModule
            .Builder()
            .enable(KotlinFeature.NewStrictNullChecks)
            .build()
}
