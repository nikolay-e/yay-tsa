package dev.yaytsa.persistence.preferences.jpa

import dev.yaytsa.persistence.preferences.entity.UserPreferencesEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserPreferencesJpaRepository : JpaRepository<UserPreferencesEntity, String>
