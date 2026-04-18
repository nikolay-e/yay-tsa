package dev.yaytsa.persistence.shared.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox", schema = "core_v2_shared")
class OutboxEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "context", nullable = false)
    var context: String = "",
    @Column(name = "payload", nullable = false)
    var payload: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)
