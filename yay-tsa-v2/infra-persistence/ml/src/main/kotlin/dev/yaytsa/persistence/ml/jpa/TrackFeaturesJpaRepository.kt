package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TrackFeaturesJpaRepository : JpaRepository<TrackFeaturesEntity, UUID>
