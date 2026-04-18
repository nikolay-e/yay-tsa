package dev.yaytsa.persistence.preferences.adapter

import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.persistence.preferences.jpa.FavoriteJpaRepository
import dev.yaytsa.persistence.preferences.jpa.PreferenceContractJpaRepository
import dev.yaytsa.persistence.preferences.jpa.UserPreferencesJpaRepository
import dev.yaytsa.persistence.preferences.mapper.toContractEntity
import dev.yaytsa.persistence.preferences.mapper.toDomain
import dev.yaytsa.persistence.preferences.mapper.toFavoriteEntities
import dev.yaytsa.persistence.preferences.mapper.toRootEntity
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class JpaUserPreferencesRepository(
    private val preferencesJpa: UserPreferencesJpaRepository,
    private val favoriteJpa: FavoriteJpaRepository,
    private val contractJpa: PreferenceContractJpaRepository,
    private val entityManager: EntityManager,
) : UserPreferencesRepository {
    @Transactional(readOnly = true)
    override fun find(userId: UserId): UserPreferencesAggregate? {
        val root = preferencesJpa.findById(userId.value).orElse(null) ?: return null
        val favorites = favoriteJpa.findByUserIdOrderByPosition(userId.value)
        val contract = contractJpa.findById(userId.value).orElse(null)
        return toDomain(root, favorites, contract)
    }

    override fun save(aggregate: UserPreferencesAggregate) {
        val rootEntity = aggregate.toRootEntity()
        val isNew = !preferencesJpa.existsById(rootEntity.userId)

        if (isNew) {
            entityManager.persist(rootEntity)
        } else {
            val updatedRows =
                entityManager
                    .createQuery(
                        """
                        UPDATE UserPreferencesEntity u SET
                            u.version = :nextVersion
                        WHERE u.userId = :userId AND u.version = :expectedVersion
                        """.trimIndent(),
                    ).setParameter("nextVersion", rootEntity.version)
                    .setParameter("userId", rootEntity.userId)
                    .setParameter("expectedVersion", aggregate.version.value - 1)
                    .executeUpdate()

            if (updatedRows == 0) {
                throw OptimisticLockException(
                    "UserPreferences ${aggregate.userId.value} was modified concurrently (expected version ${aggregate.version.value - 1})",
                )
            }

            entityManager.clear()
        }

        // Replace favorites: delete existing, flush, then insert new ones
        favoriteJpa.deleteByUserId(aggregate.userId.value)
        favoriteJpa.flush()
        favoriteJpa.saveAll(aggregate.toFavoriteEntities())

        // Replace preference contract
        val contractEntity = aggregate.toContractEntity()
        if (contractEntity != null) {
            contractJpa.save(contractEntity)
        } else {
            contractJpa.deleteById(aggregate.userId.value)
        }
    }
}
