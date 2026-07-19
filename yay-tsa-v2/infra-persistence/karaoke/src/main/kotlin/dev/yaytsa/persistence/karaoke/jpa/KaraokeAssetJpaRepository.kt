package dev.yaytsa.persistence.karaoke.jpa

import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import dev.yaytsa.shared.AudiobookGenres
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface KaraokeAssetJpaRepository : JpaRepository<KaraokeAssetEntity, UUID> {
    fun findByReadyAtIsNotNull(): List<KaraokeAssetEntity>

    // Assets separated for audiobooks before the worker started excluding them. Returned whole so
    // the worker can delete the stem files off disk (a DELETE migration could not) before the row.
    @Query(
        value =
            """
            SELECT * FROM core_v2_karaoke.assets a
            WHERE ${AudiobookGenres.EXISTS_OPEN}eg.entity_id = a.track_id${AudiobookGenres.EXISTS_CLOSE}
            """,
        nativeQuery = true,
    )
    fun findAudiobookAssets(): List<KaraokeAssetEntity>
}
