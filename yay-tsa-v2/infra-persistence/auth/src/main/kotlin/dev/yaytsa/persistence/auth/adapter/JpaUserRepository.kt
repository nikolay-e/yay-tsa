package dev.yaytsa.persistence.auth.adapter

import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.persistence.auth.TokenHasher
import dev.yaytsa.persistence.auth.jpa.ApiTokenJpaRepository
import dev.yaytsa.persistence.auth.jpa.UserJpaRepository
import dev.yaytsa.persistence.auth.mapper.toDomain
import dev.yaytsa.persistence.auth.mapper.toEntity
import dev.yaytsa.persistence.auth.mapper.toTokenEntities
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional
class JpaUserRepository(
    private val userJpa: UserJpaRepository,
    private val tokenJpa: ApiTokenJpaRepository,
    private val entityManager: EntityManager,
) : UserRepository {
    @Transactional(readOnly = true)
    override fun find(userId: UserId): UserAggregate? {
        val uuid = UUID.fromString(userId.value)
        val userEntity = userJpa.findById(uuid).orElse(null) ?: return null
        val tokens = tokenJpa.findByUserId(uuid)
        return userEntity.toDomain(tokens)
    }

    @Transactional(readOnly = true)
    override fun findByUsername(username: String): UserAggregate? {
        val userEntity = userJpa.findByUsername(username) ?: return null
        val tokens = tokenJpa.findByUserId(userEntity.id)
        return userEntity.toDomain(tokens)
    }

    @Transactional(readOnly = true)
    override fun findAll(): List<UserAggregate> =
        userJpa.findAll().map { userEntity ->
            val tokens = tokenJpa.findByUserId(userEntity.id)
            userEntity.toDomain(tokens)
        }

    @Transactional(readOnly = true)
    override fun findByApiToken(token: String): UserAggregate? {
        val tokenEntity = tokenJpa.findByTokenAndRevokedFalse(TokenHasher.hash(token)) ?: return null
        val userEntity = userJpa.findById(tokenEntity.userId).orElse(null) ?: return null
        val allTokens = tokenJpa.findByUserId(userEntity.id)
        return userEntity.toDomain(allTokens)
    }

    override fun save(aggregate: UserAggregate) {
        val entity = aggregate.toEntity()
        val existingTokenIds = tokenJpa.findByUserId(entity.id).map { it.id }.toSet()
        val tokenEntities = aggregate.toTokenEntities(existingTokenIds)

        val isNew = !userJpa.existsById(entity.id)

        if (isNew) {
            entityManager.persist(entity)
        } else {
            val updatedRows =
                entityManager
                    .createQuery(
                        """
                        UPDATE UserEntity u SET
                            u.username = :username,
                            u.passwordHash = :passwordHash,
                            u.displayName = :displayName,
                            u.email = :email,
                            u.isAdmin = :isAdmin,
                            u.isActive = :isActive,
                            u.createdAt = :createdAt,
                            u.updatedAt = :updatedAt,
                            u.lastLoginAt = :lastLoginAt,
                            u.version = :nextVersion
                        WHERE u.id = :id AND u.version = :expectedVersion
                        """.trimIndent(),
                    ).setParameter("username", entity.username)
                    .setParameter("passwordHash", entity.passwordHash)
                    .setParameter("displayName", entity.displayName)
                    .setParameter("email", entity.email)
                    .setParameter("isAdmin", entity.isAdmin)
                    .setParameter("isActive", entity.isActive)
                    .setParameter("createdAt", entity.createdAt)
                    .setParameter("updatedAt", entity.updatedAt)
                    .setParameter("lastLoginAt", entity.lastLoginAt)
                    .setParameter("nextVersion", entity.version)
                    .setParameter("id", entity.id)
                    .setParameter("expectedVersion", aggregate.version.value - 1)
                    .executeUpdate()

            if (updatedRows == 0) {
                throw OptimisticLockException(
                    "User ${aggregate.id.value} was modified concurrently (expected version ${aggregate.version.value - 1})",
                )
            }

            entityManager.clear()
        }

        // Replace all tokens: delete existing, flush, then insert new ones
        tokenJpa.deleteAll(tokenJpa.findByUserId(entity.id))
        tokenJpa.flush()
        tokenJpa.saveAll(tokenEntities)
    }
}
