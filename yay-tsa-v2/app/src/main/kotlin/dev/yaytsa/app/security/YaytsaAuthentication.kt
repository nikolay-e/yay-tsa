package dev.yaytsa.app.security

import dev.yaytsa.adapterjellyfin.DeviceBoundAuthentication
import dev.yaytsa.shared.UserId
import org.springframework.security.authentication.AbstractAuthenticationToken

class YaytsaAuthentication(
    val userId: UserId,
    private val tokenValue: String,
    override val deviceId: String? = null,
    override val deviceName: String? = null,
    val isAdmin: Boolean = false,
) : AbstractAuthenticationToken(emptyList()),
    DeviceBoundAuthentication {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): String = tokenValue

    override fun getPrincipal(): UserId = userId

    override fun getName(): String = userId.value
}
