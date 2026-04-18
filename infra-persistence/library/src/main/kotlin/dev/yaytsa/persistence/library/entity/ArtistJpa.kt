package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "artists", schema = "core_v2_library")
class ArtistJpa(
    @Id
    @Column(name = "entity_id")
    val entityId: UUID = UUID.randomUUID(),
    @Column(name = "musicbrainz_id", length = 36)
    val musicbrainzId: String? = null,
    val biography: String? = null,
    @Column(name = "formed_date")
    val formedDate: LocalDate? = null,
    @Column(name = "ended_date")
    val endedDate: LocalDate? = null,
)
