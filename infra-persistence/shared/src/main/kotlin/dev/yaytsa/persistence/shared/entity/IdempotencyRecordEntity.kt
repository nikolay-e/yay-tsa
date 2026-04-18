package dev.yaytsa.persistence.shared.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class IdempotencyRecordEntityId(
    var userId: String = "",
    var commandType: String = "",
    var idemKey: String = "",
) : Serializable

@Entity
@Table(name = "idempotency_records", schema = "core_v2_shared")
@IdClass(IdempotencyRecordEntityId::class)
class IdempotencyRecordEntity(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",
    @Id
    @Column(name = "command_type", nullable = false)
    var commandType: String = "",
    @Id
    @Column(name = "idem_key", nullable = false)
    var idemKey: String = "",
    @Column(name = "payload_hash", nullable = false)
    var payloadHash: String = "",
    @Column(name = "result_version", nullable = false)
    var resultVersion: Long = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
