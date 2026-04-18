package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "entities", schema = "core_v2_library")
class LibraryEntityJpa(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "entity_type", nullable = false, length = 20)
    val entityType: String = "",
    @Column(length = 500)
    val name: String? = null,
    @Column(name = "sort_name", length = 500)
    val sortName: String? = null,
    @Column(name = "parent_id")
    val parentId: UUID? = null,
    @Column(name = "source_path", unique = true)
    val sourcePath: String? = null,
    @Column(length = 50)
    val container: String? = null,
    @Column(name = "size_bytes")
    val sizeBytes: Long? = null,
    val mtime: OffsetDateTime? = null,
    @Column(name = "library_root")
    val libraryRoot: String? = null,
    val overview: String? = null,
    @Column(name = "search_text")
    val searchText: String? = null,
    @Column(name = "created_at")
    val createdAt: OffsetDateTime? = null,
    @Column(name = "updated_at")
    val updatedAt: OffsetDateTime? = null,
)
