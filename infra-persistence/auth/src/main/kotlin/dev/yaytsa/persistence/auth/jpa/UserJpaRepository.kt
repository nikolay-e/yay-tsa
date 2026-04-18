package dev.yaytsa.persistence.auth.jpa

import dev.yaytsa.persistence.auth.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {
    fun findByUsername(username: String): UserEntity?
}
