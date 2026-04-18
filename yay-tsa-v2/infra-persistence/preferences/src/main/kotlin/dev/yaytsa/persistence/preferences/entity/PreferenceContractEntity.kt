package dev.yaytsa.persistence.preferences.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "preference_contracts", schema = "core_v2_preferences")
class PreferenceContractEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Column(name = "hard_rules")
    var hardRules: String? = null,
    @Column(name = "soft_prefs")
    var softPrefs: String? = null,
    @Column(name = "dj_style")
    var djStyle: String? = null,
    @Column(name = "red_lines")
    var redLines: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
