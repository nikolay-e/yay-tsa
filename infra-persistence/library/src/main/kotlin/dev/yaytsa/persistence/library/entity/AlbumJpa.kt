package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "albums", schema = "core_v2_library")
class AlbumJpa(
    @Id
    @Column(name = "entity_id")
    val entityId: UUID = UUID.randomUUID(),
    @Column(name = "artist_id")
    val artistId: UUID? = null,
    @Column(name = "release_date")
    val releaseDate: LocalDate? = null,
    @Column(name = "total_tracks")
    val totalTracks: Int? = null,
    @Column(name = "total_discs")
    val totalDiscs: Int = 1,
    @Column(name = "is_complete")
    val isComplete: Boolean = true,
)
