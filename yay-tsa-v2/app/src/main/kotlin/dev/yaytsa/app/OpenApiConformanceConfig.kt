package dev.yaytsa.app

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
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
            applyNonBlankConstraints(schemas)
            applyMaxLengthConstraints(schemas)
            applyEnumConstraints(schemas)
            applyUuidConstraints(schemas)
            applyIntBoundConstraints(schemas)
            applyUuidArrayItemConstraints(schemas)
        }

    private fun applyNonBlankConstraints(schemas: Map<String, Schema<*>>) =
        eachMappedField(schemas, NON_BLANK_BODY_FIELDS) { schema ->
            schema.minLength = 1
            // minLength alone still admits a single whitespace char, which the
            // isNotBlank() handlers reject with 400 — require one non-whitespace char.
            if (schema.pattern == null) schema.pattern = "\\S"
        }

    private fun applyMaxLengthConstraints(schemas: Map<String, Schema<*>>) =
        eachMappedField(schemas, MAX_LENGTH_BODY_FIELDS) { schema, max ->
            if (schema.maxLength == null) schema.maxLength = max
        }

    private fun applyEnumConstraints(schemas: Map<String, Schema<*>>) =
        eachMappedField(schemas, ENUM_BODY_FIELDS) { schema, values ->
            @Suppress("UNCHECKED_CAST")
            (schema as? Schema<Any>)?.takeIf { it.enum.isNullOrEmpty() }?.enum = values
        }

    private fun applyUuidConstraints(schemas: Map<String, Schema<*>>) {
        eachMappedField(schemas, UUID_BODY_FIELDS) { schema -> schema.format = "uuid" }
    }

    private fun applyIntBoundConstraints(schemas: Map<String, Schema<*>>) =
        eachMappedField(schemas, NON_NEGATIVE_INT_BODY_FIELDS) { schema ->
            if (schema.minimum == null) schema.minimum = BigDecimal.ZERO
            if (schema.maximum == null) {
                // int64 (PositionTicks etc.) overflows Long on huge fuzz values and 400s;
                // cap at the format's real max so those land in the negative-test bucket.
                schema.maximum =
                    if (schema.format == "int32") BigDecimal(Int.MAX_VALUE) else BigDecimal(Long.MAX_VALUE)
            }
        }

    private fun applyUuidArrayItemConstraints(schemas: Map<String, Schema<*>>) =
        eachMappedField(schemas, UUID_ARRAY_ITEM_FIELDS) { schema -> schema.items?.format = "uuid" }

    // Range-capable GET endpoints (audio stream, karaoke stems) answer a `Range` header with
    // 206 Partial Content — a valid success spring-doc never infers. Declaring 206 on GETs keeps
    // schemathesis from reporting it as an "Undocumented HTTP status code". Harmless on GETs that
    // never serve ranges (a documented-but-unused status is not a conformance failure).
    @Bean
    fun partialContentResponseCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping::class.java) ||
                handlerMethod.hasMethodAnnotation(org.springframework.web.bind.annotation.RequestMapping::class.java)
            ) {
                val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
                if (!responses.containsKey("206")) {
                    responses.addApiResponse("206", ApiResponse().description("Partial content (range request)"))
                }
            }
            operation
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

        // DB column bounds the handlers surface as a clean 400 (DataAccessException on
        // varchar overflow) — declare them so positive fuzzing stays inside the column.
        private val MAX_LENGTH_BODY_FIELDS =
            mapOf(
                "CreateGroupRequest" to mapOf("name" to 200),
                "CreatePlaylistRequest" to mapOf("Name" to 500),
                "TransferRequest" to mapOf("toDeviceId" to 255),
                "SignalRequest" to mapOf("signal_type" to 30, "signalType" to 30),
            )

        // Closed command vocabularies the handlers reject with 400 ("Unknown command").
        private val ENUM_BODY_FIELDS =
            mapOf(
                "CommandRequest" to mapOf("command" to listOf<Any>("play", "pause", "skip_next", "skip_previous", "seek")),
            )

        private val UUID_BODY_FIELDS =
            mapOf(
                "PlaybackStartInfo" to setOf("ItemId"),
                "PlaybackProgressInfo" to setOf("ItemId"),
                "PlaybackStopInfo" to setOf("ItemId"),
                "SignalRequest" to setOf("track_id", "trackId"),
            )

        private val PLAYBACK_REPORT_INT_FIELDS =
            setOf("PositionTicks", "AudioStreamIndex", "SubtitleStreamIndex", "VolumeLevel", "EventTime")

        private val NON_NEGATIVE_INT_BODY_FIELDS =
            mapOf(
                "PlaybackStartInfo" to PLAYBACK_REPORT_INT_FIELDS,
                "PlaybackProgressInfo" to PLAYBACK_REPORT_INT_FIELDS,
                "PlaybackStopInfo" to setOf("PositionTicks", "EventTime"),
                "ScheduleRequest" to setOf("positionMs", "expected_epoch"),
            )

        // Id-array body fields the handlers treat as track ids: constraining the items to
        // format uuid keeps positive fuzzing from generating whitespace/control-char strings
        // that the isNotBlank / TrackId path rejects with a 400 (a schema-compliant-but-invalid
        // "API rejected valid data" false positive). reorderFavorites ignores unknown ids, so a
        // valid-uuid array is a clean 200. Mirrors OpenApiConfig's path-id pattern for bodies.
        private val UUID_ARRAY_ITEM_FIELDS =
            mapOf(
                "FavoriteOrderRequest" to setOf("ItemIds"),
                "CreatePlaylistRequest" to setOf("Ids"),
            )
    }
}

private fun eachMappedField(
    schemas: Map<String, Schema<*>>,
    fieldsBySchema: Map<String, Set<String>>,
    apply: (Schema<*>) -> Unit,
) = fieldsBySchema.forEach { (schemaName, fields) ->
    val properties = schemas[schemaName]?.properties ?: return@forEach
    fields.forEach { field -> properties[field]?.let(apply) }
}

private fun <V> eachMappedField(
    schemas: Map<String, Schema<*>>,
    fieldsBySchema: Map<String, Map<String, V>>,
    apply: (Schema<*>, V) -> Unit,
) = fieldsBySchema.forEach { (schemaName, fields) ->
    val properties = schemas[schemaName]?.properties ?: return@forEach
    fields.forEach { (field, value) -> properties[field]?.also { apply(it, value) } }
}
