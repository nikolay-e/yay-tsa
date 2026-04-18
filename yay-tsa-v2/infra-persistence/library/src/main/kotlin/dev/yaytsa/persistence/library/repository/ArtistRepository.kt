package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.ArtistJpa
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ArtistRepository : JpaRepository<ArtistJpa, UUID>
