package dev.yaytsa.app

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

// Conformance constraints that keep schemathesis positive fuzzing inside the servable
// domain, complementing OpenApiConfig's response-code declarations. Every entry mirrors a
// deterministic 400/415 the handlers already enforce — the spec just states it.
@Configuration
class OpenApiConformanceConfig {
    // Positional path params (queue/playlist indices) are non-negative int32s. Schemathesis
    // treats `format: int32` as annotation-only and still generates int64-range values, whose
    // 400 rejection is then reported as "API rejected schema-compliant request".
    @Bean
    fun integerPathParamBoundsCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, _ ->
            operation.parameters
                ?.filter { it.`in` == "path" }
                ?.forEach { param ->
                    val schema = param.schema
                    if (schema?.type == "integer") {
                        if (schema.minimum == null) schema.minimum = BigDecimal.ZERO
                        if (schema.maximum == null) schema.maximum = BigDecimal(Int.MAX_VALUE)
                    }
                }
            operation
        }

    // Request-body fields the handlers reject with 400: blank-rejected strings get
    // minLength 1, UUID-validated ids get format uuid.
    @Bean
    fun requestBodyConstraintsCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val schemas = openApi.components?.schemas ?: return@OpenApiCustomizer
            NON_BLANK_BODY_FIELDS.forEach { (schemaName, fields) ->
                val properties = schemas[schemaName]?.properties ?: return@forEach
                fields.forEach { field -> properties[field]?.minLength = 1 }
            }
            UUID_BODY_FIELDS.forEach { (schemaName, fields) ->
                val properties = schemas[schemaName]?.properties ?: return@forEach
                fields.forEach { field -> properties[field]?.format = "uuid" }
            }
        }

    // Multipart uploads without a body can only ever 415/400; a required request body keeps
    // positive fuzzing from sending body-less requests.
    @Bean
    fun multipartRequestBodyRequiredCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, _ ->
            val content = operation.requestBody?.content
            if (content?.containsKey("multipart/form-data") == true) {
                operation.requestBody.required = true
            }
            operation
        }

    companion object {
        private val NON_BLANK_BODY_FIELDS =
            mapOf(
                "CreateGroupRequest" to setOf("name"),
                "TransferRequest" to setOf("toDeviceId"),
                "CreatePlaylistRequest" to setOf("Name"),
            )

        private val UUID_BODY_FIELDS =
            mapOf(
                "PlaybackStartInfo" to setOf("ItemId"),
                "PlaybackProgressInfo" to setOf("ItemId"),
                "PlaybackStopInfo" to setOf("ItemId"),
            )
    }
}
