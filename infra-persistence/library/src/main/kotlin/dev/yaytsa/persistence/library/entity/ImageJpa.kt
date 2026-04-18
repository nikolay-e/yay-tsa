package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "images", schema = "core_v2_library")
class ImageJpa(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "entity_id")
    val entityId: UUID = UUID.randomUUID(),
    @Column(name = "image_type", nullable = false, length = 20)
    val imageType: String = "",
    @Column(nullable = false)
    val path: String = "",
    val width: Int? = null,
    val height: Int? = null,
    @Column(name = "size_bytes")
    val sizeBytes: Long? = null,
    val tag: String? = null,
    @Column(name = "is_primary")
    val isPrimary: Boolean = false,
    @Column(name = "created_at")
    val createdAt: OffsetDateTime? = null,
)
