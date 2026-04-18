package dev.yaytsa.app.security

import dev.yaytsa.shared.UserId
import org.springframework.security.authentication.AbstractAuthenticationToken

class YaytsaAuthentication(
    val userId: UserId,
    private val tokenValue: String,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): String = tokenValue

    override fun getPrincipal(): UserId = userId

    override fun getName(): String = userId.value
}
