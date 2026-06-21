package dev.yaytsa.app

import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.ParameterizedType

@Configuration
class OpenApiConfig {
    // Schemathesis flags every response code not declared in the OpenAPI spec as an
    // "Undocumented HTTP status code" failure. Spring-doc only emits 200 by default,
    // so all admin / lease-protected endpoints returning 401/403/404/409 explode the
    // post-deploy QA gate with false positives. Declaring the standard error codes
    // globally keeps the gate honest: real 5xx still fail, expected auth errors don't.
    @Bean
    fun globalResponsesCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, _ ->
            val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
            STANDARD_ERROR_RESPONSES.forEach { (code, description) ->
                if (!responses.containsKey(code)) {
                    responses.addApiResponse(code, problemDetailResponse(description))
                }
            }
            operation
        }

    // Handlers returning ResponseEntity<Void> / Unit answer with 204 No Content, but spring-doc
    // only infers a default 200 — so a real 204 trips schemathesis "Undocumented HTTP status code".
    // Declaring 204 for no-content handlers (e.g. POST /v1/client-errors) keeps the gate honest
    // without adding swagger annotations to the thin adapters.
    @Bean
    fun noContentResponseCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (returnsNoContent(handlerMethod)) {
                val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
                if (!responses.containsKey("204")) {
                    responses.addApiResponse("204", ApiResponse().description("No content"))
                }
            }
            operation
        }

    private fun returnsNoContent(handlerMethod: HandlerMethod): Boolean {
        val method = handlerMethod.method
        if (method.returnType == Void.TYPE) return true
        val generic = method.genericReturnType
        if (generic is ParameterizedType) {
            val raw = generic.rawType
            if (raw is Class<*> && ResponseEntity::class.java.isAssignableFrom(raw)) {
                return generic.actualTypeArguments.firstOrNull() == Void::class.java
            }
        }
        return false
    }

    // Path ids are UUIDs, device ids, or positional indices — never semicolons, percent escapes
    // or control characters. Without a declared pattern, fuzzers legitimately generate strings
    // like `®­*¼²â,»;_ˆ` as "schema-compliant" path params; Spring Security's StrictHttpFirewall
    // then rejects the encoded `;` before any controller, and the resulting 400 is reported as
    // "API rejected schema-compliant request". Declaring the real id alphabet in the spec makes
    // positive fuzzing generate servable ids and keeps the firewall rejection a negative-test pass.
    @Bean
    fun pathIdPatternCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, _ ->
            operation.parameters
                ?.filter { it.`in` == "path" }
                ?.forEach { param ->
                    val schema = param.schema
                    if (schema?.type == "string" && schema.pattern == null && schema.format == null) {
                        schema.pattern = PATH_ID_PATTERN
                    }
                }
            operation
        }

    private fun problemDetailResponse(description: String): ApiResponse =
        ApiResponse()
            .description(description)
            .content(Content().addMediaType("application/problem+json", MediaType().schema(ObjectSchema())))

    companion object {
        private const val PATH_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._@:-]{0,127}$"

        private val STANDARD_ERROR_RESPONSES =
            linkedMapOf(
                "400" to "Bad request — validation or domain invariant violated",
                "401" to "Unauthorized — missing or invalid token",
                "403" to "Forbidden — admin/lease/ownership check failed",
                "404" to "Not found",
                "409" to "Conflict — OCC version mismatch or storage conflict",
                "429" to "Too Many Requests — rate limit exceeded",
                "500" to "Internal server error",
            )
    }
}
