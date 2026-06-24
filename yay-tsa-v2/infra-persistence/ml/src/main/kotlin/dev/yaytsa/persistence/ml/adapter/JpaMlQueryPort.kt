package dev.yaytsa.persistence.ml.adapter

import dev.yaytsa.application.ml.port.EmbeddingCoverage
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.domain.ml.TasteProfile
import dev.yaytsa.domain.ml.TrackFeatures
import dev.yaytsa.domain.ml.UserTrackAffinity
import dev.yaytsa.persistence.ml.jpa.TasteClustersJpaRepository
import dev.yaytsa.persistence.ml.jpa.TasteProfileJpaRepository
import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import dev.yaytsa.persistence.ml.jpa.UserTrackAffinityJpaRepository
import dev.yaytsa.persistence.ml.mapper.MlMappers
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class JpaMlQueryPort(
    private val trackFeaturesJpa: TrackFeaturesJpaRepository,
    private val tasteProfileJpa: TasteProfileJpaRepository,
    private val affinityJpa: UserTrackAffinityJpaRepository,
    private val tasteClustersJpa: TasteClustersJpaRepository,
    private val jdbc: JdbcTemplate,
) : MlQueryPort {
    override fun getTrackFeatures(trackId: TrackId): TrackFeatures? {
        val id = UUID.fromString(trackId.value)
        val entity = trackFeaturesJpa.findById(id).orElse(null) ?: return null
        return MlMappers.toDomain(entity)
    }

    override fun getTasteProfile(userId: UserId): TasteProfile? {
        val id = UUID.fromString(userId.value)
        val entity = tasteProfileJpa.findById(id).orElse(null) ?: return null
        return MlMappers.toDomain(entity)
    }

    override fun getUserTrackAffinity(
        userId: UserId,
        trackId: TrackId,
    ): UserTrackAffinity? {
        val uid = UUID.fromString(userId.value)
        val tid = UUID.fromString(trackId.value)
        val entity = affinityJpa.findByUserIdAndTrackId(uid, tid) ?: return null
        return MlMappers.toDomain(entity)
    }

    override fun getTopAffinities(
        userId: UserId,
        limit: Int,
    ): List<UserTrackAffinity> {
        val uid = UUID.fromString(userId.value)
        return affinityJpa
            .findByUserIdOrderByAffinityScoreDesc(uid, PageRequest.of(0, limit.coerceIn(1, MlQueryPort.MAX_QUERY_LIMIT)))
            .map { MlMappers.toDomain(it) }
    }

    override fun findSimilarTracks(
        seedTrackId: TrackId,
        limit: Int,
    ): List<TrackId> {
        val seed = UUID.fromString(seedTrackId.value)
        val cappedLimit = limit.coerceIn(1, MlQueryPort.MAX_QUERY_LIMIT)
        val ids =
            trackFeaturesJpa
                .findSimilarByMert(seed, cappedLimit)
                .ifEmpty { trackFeaturesJpa.findSimilarByClap(seed, cappedLimit) }
                .ifEmpty { trackFeaturesJpa.findSimilarByDiscogs(seed, cappedLimit) }
        return ids.map { TrackId(it.toString()) }
    }

    override fun findTracksByClapVector(
        vector: FloatArray,
        limit: Int,
    ): List<TrackId> {
        if (vector.isEmpty()) return emptyList()
        val cappedLimit = limit.coerceIn(1, MlQueryPort.MAX_QUERY_LIMIT)
        val literal = vector.joinToString(prefix = "[", postfix = "]", separator = ",")
        return trackFeaturesJpa.findByClapVector(literal, cappedLimit).map { TrackId(it.toString()) }
    }

    override fun getTasteClusterRepresentatives(userId: UserId): List<TrackId> {
        val uid = UUID.fromString(userId.value)
        return tasteClustersJpa.findRepresentativesByUserId(uid).map { TrackId(it.toString()) }
    }

    override fun findRadioPool(
        seedTrackId: TrackId,
        poolSize: Int,
    ): List<TrackId> {
        val seed = UUID.fromString(seedTrackId.value)
        val capped = poolSize.coerceIn(1, MlQueryPort.MAX_QUERY_LIMIT)
        // Raise HNSW ef_search for the duration of this read transaction so the wide pool is
        // actually recalled. pgvector defaults ef_search to 40 — without this, asking for 200
        // candidates silently returns ~40 and the diversifier has nothing to diversify over.
        // SET LOCAL is bound to the current @Transactional connection, which JdbcTemplate and the
        // JPA queries below share, so it applies to the kNN scans that follow. The value is an int,
        // not user input — safe to inline.
        jdbc.execute("SET LOCAL hnsw.ef_search = ${maxOf(capped, RADIO_EF_SEARCH)}")
        val ids =
            trackFeaturesJpa
                .findSimilarByMert(seed, capped)
                .ifEmpty { trackFeaturesJpa.findSimilarByClap(seed, capped) }
                .ifEmpty { trackFeaturesJpa.findSimilarByDiscogs(seed, capped) }
        return ids.map { TrackId(it.toString()) }
    }

    override fun embeddingCoverage(): EmbeddingCoverage =
        EmbeddingCoverage(
            total = trackFeaturesJpa.count(),
            mert = trackFeaturesJpa.countWithMert(),
            clap = trackFeaturesJpa.countWithClap(),
            discogs = trackFeaturesJpa.countWithDiscogs(),
            musicnn = trackFeaturesJpa.countWithMusicnn(),
        )

    private companion object {
        const val RADIO_EF_SEARCH = 200
    }
}
