package dev.yaytsa.persistence.preferences.jpa

import dev.yaytsa.persistence.preferences.entity.PreferenceContractEntity
import org.springframework.data.jpa.repository.JpaRepository

interface PreferenceContractJpaRepository : JpaRepository<PreferenceContractEntity, String>
