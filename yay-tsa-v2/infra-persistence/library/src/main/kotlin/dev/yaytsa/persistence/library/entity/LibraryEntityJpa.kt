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
    var name: String? = null,
    @Column(name = "sort_name", length = 500)
    var sortName: String? = null,
    @Column(name = "parent_id")
    var parentId: UUID? = null,
    @Column(name = "source_path", unique = true)
    var sourcePath: String? = null,
    @Column(length = 50)
    var container: String? = null,
    @Column(name = "size_bytes")
    var sizeBytes: Long? = null,
    var mtime: OffsetDateTime? = null,
    @Column(name = "library_root")
    var libraryRoot: String? = null,
    val overview: String? = null,
    @Column(name = "search_text")
    var searchText: String? = null,
    @Column(name = "created_at")
    val createdAt: OffsetDateTime? = null,
    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime? = null,
)
