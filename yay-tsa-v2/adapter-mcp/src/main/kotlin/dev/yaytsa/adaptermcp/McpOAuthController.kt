package dev.yaytsa.adaptermcp

import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@RestController
class McpOAuthController(
    private val authQueries: AuthQueries,
    private val clientRepository: OAuthClientRepository,
    private val codeStore: OAuthAuthorizationCodeStore,
    private val tokenIssuer: OAuthTokenIssuer,
    @Value("\${yaytsa.oauth.public-api-prefix:/api}")
    private val publicApiPrefix: String,
) {
    data class ClientRegistrationRequest(
        @JsonProperty("redirect_uris")
        val redirectUris: List<String> = emptyList(),
        @JsonProperty("client_name")
        val clientName: String? = null,
    )

    @PostMapping("/oauth/register", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun register(
        @RequestBody request: ClientRegistrationRequest,
    ): ResponseEntity<Any> {
        val uris = request.redirectUris.distinct()
        if (uris.isEmpty() || uris.size > MAX_REDIRECT_URIS || uris.any { !isAcceptableRedirectUri(it) }) {
            return oauthError("invalid_redirect_uri", "redirect_uris must contain 1-$MAX_REDIRECT_URIS absolute https URLs")
        }
        val client =
            OAuthClient(
                id = UUID.randomUUID(),
                clientName = (request.clientName?.trim()?.take(MAX_CLIENT_NAME_LENGTH)).takeUnless { it.isNullOrBlank() } ?: "MCP Client",
                redirectUris = uris,
            )
        clientRepository.save(client)
        log.info("Registered OAuth client {} ({}) for {}", client.id, client.clientName, uris)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
            mapOf(
                "client_id" to client.id.toString(),
                "client_name" to client.clientName,
                "redirect_uris" to client.redirectUris,
                "token_endpoint_auth_method" to "none",
                "grant_types" to listOf("authorization_code"),
                "response_types" to listOf("code"),
            ),
        )
    }

    @GetMapping("/oauth/authorize", produces = [MediaType.TEXT_HTML_VALUE])
    fun authorizeForm(
        @RequestParam params: Map<String, String>,
    ): ResponseEntity<String> {
        val client = resolveClient(params) ?: return invalidClientPage()
        val paramError = validateAuthorizeParams(params)
        if (paramError != null) return redirect(params.getValue("redirect_uri"), "error" to paramError, "state" to params["state"])
        return loginPage(client, params, errorMessage = null)
    }

    @PostMapping(
        "/oauth/authorize",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun authorizeSubmit(
        @RequestParam params: Map<String, String>,
    ): ResponseEntity<String> {
        val client = resolveClient(params) ?: return invalidClientPage()
        val redirectUri = params.getValue("redirect_uri")
        val paramError = validateAuthorizeParams(params)
        if (paramError != null) return redirect(redirectUri, "error" to paramError, "state" to params["state"])

        val user = params["username"]?.let { authQueries.findByUsername(it) }?.takeIf { it.isActive }
        val passwordValid =
            user != null &&
                try {
                    BCrypt.checkpw(params["password"].orEmpty(), user.passwordHash)
                } catch (_: IllegalArgumentException) {
                    false
                }
        if (user == null || !passwordValid) {
            return loginPage(client, params, errorMessage = "Invalid username or password.")
        }

        val code =
            codeStore.issue(client.id, redirectUri, params.getValue("code_challenge"), user.id.value, params["scope"])
                ?: return redirect(redirectUri, "error" to "temporarily_unavailable", "state" to params["state"])
        return redirect(redirectUri, "code" to code, "state" to params["state"])
    }

    @PostMapping(
        "/oauth/token",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun token(
        @RequestParam params: Map<String, String>,
    ): ResponseEntity<Any> {
        if (params["grant_type"] != "authorization_code") {
            return oauthError("unsupported_grant_type", "Only authorization_code is supported")
        }
        val code = params["code"]
        val redirectUri = params["redirect_uri"]
        val clientId = params["client_id"]
        val codeVerifier = params["code_verifier"]
        if (listOf(code, redirectUri, clientId, codeVerifier).any { it.isNullOrBlank() }) {
            return oauthError("invalid_request", "code, code_verifier, client_id and redirect_uri are required")
        }
        val entry = codeStore.redeem(code!!)
        val client = entry?.let { clientRepository.findById(it.clientId) }
        val computedChallenge =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier!!.toByteArray(Charsets.US_ASCII)))
        val grantFailure =
            when {
                entry == null -> "Unknown, expired or already used code"
                entry.clientId.toString() != clientId || entry.redirectUri != redirectUri ->
                    "client_id or redirect_uri does not match the authorization request"
                !MessageDigest.isEqual(computedChallenge.toByteArray(), entry.codeChallenge.toByteArray()) -> "PKCE verification failed"
                client == null -> "Client no longer registered"
                else -> null
            }
        if (grantFailure != null) return oauthError("invalid_grant", grantFailure)

        val accessToken =
            tokenIssuer.mintDeviceToken(UserId(entry!!.userId), client!!.clientName)
                ?: return oauthError("temporarily_unavailable", "Could not create session token, please retry", HttpStatus.SERVICE_UNAVAILABLE)

        val body = mutableMapOf<String, Any>("access_token" to accessToken, "token_type" to "Bearer")
        entry.scope?.takeIf { it.isNotBlank() }?.let { body["scope"] = it }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    private fun resolveClient(params: Map<String, String>): OAuthClient? {
        val redirectUri = params["redirect_uri"] ?: return null
        val id = params["client_id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val client = clientRepository.findById(id) ?: return null
        return if (redirectUri in client.redirectUris) client else null
    }

    private fun validateAuthorizeParams(params: Map<String, String>): String? =
        when {
            params["response_type"] != "code" -> "unsupported_response_type"
            params["code_challenge"].isNullOrBlank() || params["code_challenge_method"] != "S256" -> "invalid_request"
            else -> null
        }

    private fun redirect(
        redirectUri: String,
        vararg queryParams: Pair<String, String?>,
    ): ResponseEntity<String> {
        val query =
            queryParams
                .filter { !it.second.isNullOrEmpty() }
                .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, Charsets.UTF_8)}" }
        val location = redirectUri + (if (redirectUri.contains('?')) '&' else '?') + query
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .header("Location", location)
            .cacheControl(CacheControl.noStore())
            .build()
    }

    private fun invalidClientPage(): ResponseEntity<String> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_HTML)
            .body(OAuthHtmlPages.invalidClient())

    private fun loginPage(
        client: OAuthClient,
        params: Map<String, String>,
        errorMessage: String?,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(if (errorMessage == null) HttpStatus.OK else HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .body(
                OAuthHtmlPages.loginForm(
                    LoginFormModel(
                        actionPath = "$publicApiPrefix/oauth/authorize",
                        clientId = client.id.toString(),
                        clientName = client.clientName,
                        redirectUri = params.getValue("redirect_uri"),
                        state = params["state"],
                        codeChallenge = params.getValue("code_challenge"),
                        scope = params["scope"],
                        errorMessage = errorMessage,
                    ),
                ),
            )

    private fun oauthError(
        error: String,
        description: String,
        status: HttpStatus = HttpStatus.BAD_REQUEST,
    ): ResponseEntity<Any> =
        ResponseEntity
            .status(status)
            .cacheControl(CacheControl.noStore())
            .body(mapOf("error" to error, "error_description" to description))

    companion object {
        private val log = LoggerFactory.getLogger(McpOAuthController::class.java)
        private const val MAX_REDIRECT_URIS = 10
        private const val MAX_CLIENT_NAME_LENGTH = 255
    }
}
