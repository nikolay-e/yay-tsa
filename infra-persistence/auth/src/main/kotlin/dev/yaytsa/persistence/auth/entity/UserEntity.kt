package dev.yaytsa.persistence.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "core_v2_auth")
class UserEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),
    @Column(name = "username", nullable = false, columnDefinition = "citext")
    var username: String = "",
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",
    @Column(name = "display_name")
    var displayName: String? = null,
    @Column(name = "email", columnDefinition = "citext")
    var email: String? = null,
    @Column(name = "is_admin", nullable = false)
    var isAdmin: Boolean = false,
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
