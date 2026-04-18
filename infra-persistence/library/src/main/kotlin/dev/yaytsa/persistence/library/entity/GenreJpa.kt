package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "genres", schema = "core_v2_library")
class GenreJpa(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(unique = true, length = 255)
    val name: String = "",
)
