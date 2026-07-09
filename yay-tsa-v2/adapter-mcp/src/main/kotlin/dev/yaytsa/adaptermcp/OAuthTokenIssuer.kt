package dev.yaytsa.adaptermcp

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.Hashing
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.UUID

@Component
class OAuthTokenIssuer(
    private val authQueries: AuthQueries,
    private val authUseCases: AuthUseCases,
    @Qualifier("mcpCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    private val secureRandom = SecureRandom()

    fun mintDeviceToken(
        userId: UserId,
        deviceName: String,
    ): String? {
        val tokenValue = generateToken()
        val tokenId = ApiTokenId(UUID.randomUUID().toString())
        val deviceId = DeviceId("mcp-${UUID.randomUUID().toString().take(8)}")
        repeat(2) { attempt ->
            val user = authQueries.findUser(userId) ?: return null
            val cmd = CreateApiToken(user.id, tokenId, tokenValue, deviceId, deviceName.take(MAX_DEVICE_NAME_LENGTH), null)
            val ctx = ctxFactory.create(user.id, user.version)
            when (val result = authUseCases.execute(cmd, ctx)) {
                is CommandResult.Success -> return tokenValue
                is CommandResult.Failed ->
                    log.warn("OAuth CreateApiToken attempt {} failed for user {}: {}", attempt + 1, user.id.value, result.failure)
            }
        }
        return null
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Hashing.hexEncode(bytes)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OAuthTokenIssuer::class.java)
        private const val TOKEN_BYTES = 32
        private const val MAX_DEVICE_NAME_LENGTH = 64
    }
}
