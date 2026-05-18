package dev.yaytsa.app

import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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

    private fun problemDetailResponse(description: String): ApiResponse =
        ApiResponse()
            .description(description)
            .content(Content().addMediaType("application/problem+json", MediaType().schema(ObjectSchema())))

    companion object {
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
