package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.TasteClusterKey
import dev.yaytsa.persistence.ml.entity.TasteClustersEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TasteClustersJpaRepository : JpaRepository<TasteClustersEntity, TasteClusterKey> {
    @Query(
        value =
            """
            SELECT representative_track_id
            FROM core_v2_ml.taste_clusters
            WHERE user_id = :userId
            ORDER BY size DESC
            """,
        nativeQuery = true,
    )
    fun findRepresentativesByUserId(
        @Param("userId") userId: UUID,
    ): List<UUID>
}
