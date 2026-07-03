package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.GenreJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface GenreRepository : JpaRepository<GenreJpa, UUID> {
    fun findByName(name: String): GenreJpa?

    @Query(
        value =
            """
            SELECT g.name AS "name",
                   count(*) AS "songCount",
                   count(DISTINCT t.album_id) AS "albumCount"
            FROM core_v2_library.genres g
            JOIN core_v2_library.entity_genres eg ON eg.genre_id = g.id
            JOIN core_v2_library.entities e ON e.id = eg.entity_id AND e.entity_type = 'TRACK'
            LEFT JOIN core_v2_library.audio_tracks t ON t.entity_id = e.id
            GROUP BY g.name
            ORDER BY g.name
            """,
        nativeQuery = true,
    )
    fun findGenreStatistics(): List<GenreCountsRow>

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

interface GenreCountsRow {
    fun getName(): String

    fun getSongCount(): Long

    fun getAlbumCount(): Long
}
