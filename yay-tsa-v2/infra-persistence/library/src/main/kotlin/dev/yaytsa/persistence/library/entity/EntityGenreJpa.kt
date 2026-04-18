package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

data class EntityGenreId(
    val entityId: UUID = UUID.randomUUID(),
    val genreId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "entity_genres", schema = "core_v2_library")
@IdClass(EntityGenreId::class)
class EntityGenreJpa(
    @Id
    @Column(name = "entity_id")
    val entityId: UUID = UUID.randomUUID(),
    @Id
    @Column(name = "genre_id")
    val genreId: UUID = UUID.randomUUID(),
)
