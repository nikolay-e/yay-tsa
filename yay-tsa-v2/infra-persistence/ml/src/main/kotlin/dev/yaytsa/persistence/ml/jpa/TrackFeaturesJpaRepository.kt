package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TrackFeaturesJpaRepository : JpaRepository<TrackFeaturesEntity, UUID> {
    @Query(
        value =
            """
            SELECT track_id
            FROM core_v2_ml.track_features
            WHERE track_id <> :seed
              AND embedding_mert IS NOT NULL
            ORDER BY embedding_mert <=> (
                SELECT embedding_mert FROM core_v2_ml.track_features WHERE track_id = :seed
            )
            LIMIT :lim
            """,
        nativeQuery = true,
    )
    fun findSimilarByMert(
        @Param("seed") seed: UUID,
        @Param("lim") limit: Int,
    ): List<UUID>

    @Query(
        value =
            """
            SELECT track_id
            FROM core_v2_ml.track_features
            WHERE track_id <> :seed
              AND embedding_clap IS NOT NULL
            ORDER BY embedding_clap <=> (
                SELECT embedding_clap FROM core_v2_ml.track_features WHERE track_id = :seed
            )
            LIMIT :lim
            """,
        nativeQuery = true,
    )
    fun findSimilarByClap(
        @Param("seed") seed: UUID,
        @Param("lim") limit: Int,
    ): List<UUID>

    @Query(
        value =
            """
            SELECT track_id
            FROM core_v2_ml.track_features
            WHERE track_id <> :seed
              AND embedding_discogs IS NOT NULL
            ORDER BY embedding_discogs <=> (
                SELECT embedding_discogs FROM core_v2_ml.track_features WHERE track_id = :seed
            )
            LIMIT :lim
            """,
        nativeQuery = true,
    )
    fun findSimilarByDiscogs(
        @Param("seed") seed: UUID,
        @Param("lim") limit: Int,
    ): List<UUID>
}
