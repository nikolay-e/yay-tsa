package dev.yaytsa.persistence.preferences.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_preferences", schema = "core_v2_preferences")
class UserPreferencesEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
