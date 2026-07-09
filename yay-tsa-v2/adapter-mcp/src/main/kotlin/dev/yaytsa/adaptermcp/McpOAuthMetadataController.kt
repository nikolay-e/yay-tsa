package dev.yaytsa.adaptermcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class McpOAuthMetadataController(
    @Value("\${yaytsa.oauth.public-base-url:https://yay-tsa.com}")
    private val publicBaseUrl: String,
    @Value("\${yaytsa.oauth.public-api-prefix:/api}")
    private val publicApiPrefix: String,
) {
    @GetMapping("/.well-known/oauth-authorization-server", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun authorizationServerMetadata(): Map<String, Any> =
        mapOf(
            "issuer" to publicBaseUrl,
            "authorization_endpoint" to "$publicBaseUrl$publicApiPrefix/oauth/authorize",
            "token_endpoint" to "$publicBaseUrl$publicApiPrefix/oauth/token",
            "registration_endpoint" to "$publicBaseUrl$publicApiPrefix/oauth/register",
            "response_types_supported" to listOf("code"),
            "grant_types_supported" to listOf("authorization_code"),
            "code_challenge_methods_supported" to listOf("S256"),
            "token_endpoint_auth_methods_supported" to listOf("none"),
        )

    @GetMapping(
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-protected-resource/api/mcp",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun protectedResourceMetadata(): Map<String, Any> =
        mapOf(
            "resource" to "$publicBaseUrl$publicApiPrefix/mcp",
            "authorization_servers" to listOf(publicBaseUrl),
            "bearer_methods_supported" to listOf("header"),
        )
}
