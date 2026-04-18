package dev.yaytsa.persistence.karaoke.adapter

import dev.yaytsa.application.karaoke.port.KaraokeQueryPort
import dev.yaytsa.domain.karaoke.KaraokeAsset
import dev.yaytsa.persistence.karaoke.jpa.KaraokeAssetJpaRepository
import dev.yaytsa.persistence.karaoke.mapper.KaraokeMappers
import dev.yaytsa.shared.TrackId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class JpaKaraokeQueryPort(
    private val assetJpa: KaraokeAssetJpaRepository,
) : KaraokeQueryPort {
    override fun getAsset(trackId: TrackId): KaraokeAsset? {
        val id = UUID.fromString(trackId.value)
        val entity = assetJpa.findById(id).orElse(null) ?: return null
        return KaraokeMappers.toDomain(entity)
    }

    override fun getReadyTrackIds(): Set<TrackId> =
        assetJpa
            .findByReadyAtIsNotNull()
            .map { TrackId(it.trackId.toString()) }
            .toSet()
}
