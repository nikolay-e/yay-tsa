package dev.yaytsa.application.auth

import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.UserId

class AuthQueries(
    private val userRepo: UserRepository,
) {
    fun findUser(userId: UserId): UserAggregate? = userRepo.find(userId)

    fun findByUsername(username: String): UserAggregate? = userRepo.findByUsername(username)

    fun findByApiToken(token: String): UserAggregate? = userRepo.findByApiToken(token)

    fun listAll(): List<UserAggregate> = userRepo.findAll()
}
