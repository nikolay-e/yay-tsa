package dev.yaytsa.persistence.ml.adapter

import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.domain.ml.TasteProfile
import dev.yaytsa.domain.ml.TrackFeatures
import dev.yaytsa.domain.ml.UserTrackAffinity
import dev.yaytsa.persistence.ml.jpa.TasteProfileJpaRepository
import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import dev.yaytsa.persistence.ml.jpa.UserTrackAffinityJpaRepository
import dev.yaytsa.persistence.ml.mapper.MlMappers
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class JpaMlQueryPort(
    private val trackFeaturesJpa: TrackFeaturesJpaRepository,
    private val tasteProfileJpa: TasteProfileJpaRepository,
    private val affinityJpa: UserTrackAffinityJpaRepository,
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
}
