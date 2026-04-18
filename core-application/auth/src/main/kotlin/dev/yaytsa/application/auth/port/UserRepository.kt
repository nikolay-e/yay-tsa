package dev.yaytsa.application.auth.port

import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.UserId

interface UserRepository {
    fun find(userId: UserId): UserAggregate?

    fun findByUsername(username: String): UserAggregate?

    fun findByApiToken(token: String): UserAggregate?

    fun findAll(): List<UserAggregate>

    fun save(aggregate: UserAggregate)
}
