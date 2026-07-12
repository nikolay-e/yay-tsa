package dev.yaytsa.app

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.module.SimpleModule
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

    // PostgreSQL text columns cannot store a NUL (0x00) byte: it raises SQLState 22021
    // ("invalid byte sequence for encoding UTF8") deep in the JDBC batch, which Hibernate's
    // SqlExceptionHelper logs as ERROR (with the raw failing SQL) BEFORE any handler runs —
    // under a fuzz burst that floods the log and masks real errors. No legitimate input ever
    // carries a NUL, so strip it from every deserialized string at the edge (mirrors the
    // search-path scrub, applied to every write field at once). Delegates to the real String
    // deserializer so all coercion behaviour is preserved.
    @Bean
    fun nulStrippingModule(): SimpleModule {
        val module = SimpleModule()
        module.setDeserializerModifier(
            object : BeanDeserializerModifier() {
                override fun modifyDeserializer(
                    config: DeserializationConfig,
                    beanDesc: BeanDescription,
                    deserializer: JsonDeserializer<*>,
                ): JsonDeserializer<*> =
                    if (beanDesc.beanClass == String::class.java) {
                        NulStrippingStringDeserializer(deserializer)
                    } else {
                        deserializer
                    }
            },
        )
        return module
    }
}

private class NulStrippingStringDeserializer(
    private val delegate: JsonDeserializer<*>,
) : JsonDeserializer<String>() {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): String? {
        val value = delegate.deserialize(p, ctxt) as String?
        return value?.let { if (it.indexOf(NUL) >= 0) it.filter { c -> c.code != 0 } else it }
    }

    private companion object {
        val NUL = 0.toChar()
    }
}
