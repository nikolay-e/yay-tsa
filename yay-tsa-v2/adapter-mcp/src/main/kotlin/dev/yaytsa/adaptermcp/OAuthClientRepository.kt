package dev.yaytsa.adaptermcp

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.net.URI
import java.util.UUID

data class OAuthClient(
    val id: UUID,
    val clientName: String,
    val redirectUris: List<String>,
)

private const val MAX_REDIRECT_URI_LENGTH = 2000

internal fun isAcceptableRedirectUri(uri: String): Boolean {
    if (uri.length > MAX_REDIRECT_URI_LENGTH) return false
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (!parsed.isAbsolute || parsed.fragment != null || parsed.host.isNullOrBlank()) return false
    return when (parsed.scheme) {
        "https" -> true
        "http" -> parsed.host == "localhost" || parsed.host == "127.0.0.1"
        else -> false
    }
}

@Repository
class OAuthClientRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun save(client: OAuthClient) {
        jdbcTemplate.update(
            "INSERT INTO core_v2_auth.oauth_clients (id, client_name, redirect_uris) VALUES (?, ?, ?)",
            client.id,
            client.clientName,
            client.redirectUris.joinToString("\n"),
        )
    }

    fun findById(id: UUID): OAuthClient? =
        jdbcTemplate
            .query(
                "SELECT id, client_name, redirect_uris FROM core_v2_auth.oauth_clients WHERE id = ?",
                { rs, _ ->
                    OAuthClient(
                        id = rs.getObject("id", UUID::class.java),
                        clientName = rs.getString("client_name"),
                        redirectUris = rs.getString("redirect_uris").split("\n").filter { it.isNotBlank() },
                    )
                },
                id,
            ).firstOrNull()
}
