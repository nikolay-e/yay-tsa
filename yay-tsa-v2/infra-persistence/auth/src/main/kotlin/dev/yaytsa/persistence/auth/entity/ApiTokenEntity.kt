package dev.yaytsa.persistence.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "api_tokens", schema = "core_v2_auth")
class ApiTokenEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),
    @Column(name = "token", nullable = false)
    var token: String = "",
    @Column(name = "device_id", nullable = false)
    var deviceId: String = "",
    @Column(name = "device_name")
    var deviceName: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false,
)
