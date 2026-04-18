package dev.yaytsa.persistence.preferences.adapter

import dev.yaytsa.application.preferences.port.PreferencesQueryPort
import dev.yaytsa.domain.preferences.PreferenceContract
import dev.yaytsa.persistence.preferences.jpa.PreferenceContractJpaRepository
import dev.yaytsa.persistence.preferences.mapper.toDomain
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(readOnly = true)
class JpaPreferencesQueryPort(
    private val contractJpa: PreferenceContractJpaRepository,
) : PreferencesQueryPort {
    override fun getPreferenceContract(userId: UserId): PreferenceContract? {
        val entity = contractJpa.findById(userId.value).orElse(null) ?: return null
        return entity.toDomain()
    }
}
