package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.UserTrackAffinityEntity
import dev.yaytsa.persistence.ml.entity.UserTrackAffinityId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserTrackAffinityJpaRepository : JpaRepository<UserTrackAffinityEntity, UserTrackAffinityId> {
    fun findByUserIdAndTrackId(
        userId: UUID,
        trackId: UUID,
    ): UserTrackAffinityEntity?

    // affinity_score is a lifetime accumulator (AffinityAggregator only ever adds to it, never
    // decays it), so ordering by the raw column would let a handful of tracks played heavily
    // months ago permanently dominate every top-N pull — the same tracks resurface in Daily Mix
    // and, via getTasteClusterRepresentatives seeding from this same data, in Discover too. Decay
    // is applied at read time (not on write) so a track's rank fades continuously even while it
    // sits unplayed and receives no new aggregator upserts. 21-day half-life: a track played
    // heavily last week still dominates, one from ~3 months ago has faded to background noise.
    @Query(
        value = """
            SELECT * FROM core_v2_ml.user_track_affinity
            WHERE user_id = :userId
            ORDER BY affinity_score * exp(
                -ln(2) * extract(epoch FROM (now() - COALESCE(last_signal_at, updated_at))) / (86400.0 * :halfLifeDays)
            ) DESC
            LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findByUserIdOrderByDecayedScoreDesc(
        @Param("userId") userId: UUID,
        @Param("halfLifeDays") halfLifeDays: Double,
        @Param("limit") limit: Int,
    ): List<UserTrackAffinityEntity>
}
