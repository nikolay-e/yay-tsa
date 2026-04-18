package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.GenreJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface GenreRepository : JpaRepository<GenreJpa, UUID> {
    fun findByName(name: String): GenreJpa?

    @Modifying
    @Query(
        value = "INSERT INTO core_v2_library.genres (id, name) VALUES (:id, :name) ON CONFLICT (name) DO NOTHING",
        nativeQuery = true,
    )
    fun upsertByName(
        id: UUID,
        name: String,
    )
}
