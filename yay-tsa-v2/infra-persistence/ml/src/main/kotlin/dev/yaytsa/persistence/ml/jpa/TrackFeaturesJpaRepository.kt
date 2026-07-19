package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import dev.yaytsa.shared.AudiobookGenres
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface TrackFeaturesJpaRepository : JpaRepository<TrackFeaturesEntity, UUID> {
    // One-shot self-healing cleanup: removes embeddings computed for audiobooks before the worker
    // started excluding them. Runs at the start of each extraction cycle; a no-op once drained.
    @Transactional
    @Modifying
    @Query(
        value =
            """
            DELETE FROM core_v2_ml.track_features tf
            WHERE ${AudiobookGenres.EXISTS_OPEN}eg.entity_id = tf.track_id${AudiobookGenres.EXISTS_CLOSE}
            """,
        nativeQuery = true,
    )
    fun deleteAudiobookFeatures(): Int

    @Query(
        value =
            """
            SELECT track_id
            FROM core_v2_ml.track_features
            WHERE track_id <> :seed
              AND embedding_mert IS NOT NULL
              AND (SELECT embedding_mert FROM core_v2_ml.track_features WHERE track_id = :seed) IS NOT NULL
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
              AND (SELECT embedding_clap FROM core_v2_ml.track_features WHERE track_id = :seed) IS NOT NULL
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
              AND (SELECT embedding_discogs FROM core_v2_ml.track_features WHERE track_id = :seed) IS NOT NULL
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

    @Query(
        value =
            """
            SELECT track_id
            FROM core_v2_ml.track_features
            WHERE embedding_clap IS NOT NULL
            ORDER BY embedding_clap <=> CAST(:vec AS vector(512))
            LIMIT :lim
            """,
        nativeQuery = true,
    )
    fun findByClapVector(
        @Param("vec") vector: String,
        @Param("lim") limit: Int,
    ): List<UUID>

    @Query(value = "SELECT count(*) FROM core_v2_ml.track_features WHERE embedding_mert IS NOT NULL", nativeQuery = true)
    fun countWithMert(): Long

    @Query(value = "SELECT count(*) FROM core_v2_ml.track_features WHERE embedding_clap IS NOT NULL", nativeQuery = true)
    fun countWithClap(): Long

    @Query(value = "SELECT count(*) FROM core_v2_ml.track_features WHERE embedding_discogs IS NOT NULL", nativeQuery = true)
    fun countWithDiscogs(): Long

    @Query(value = "SELECT count(*) FROM core_v2_ml.track_features WHERE embedding_musicnn IS NOT NULL", nativeQuery = true)
    fun countWithMusicnn(): Long
}
