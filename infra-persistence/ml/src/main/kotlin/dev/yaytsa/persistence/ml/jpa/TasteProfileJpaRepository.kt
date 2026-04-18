package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.TasteProfileEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TasteProfileJpaRepository : JpaRepository<TasteProfileEntity, UUID>
