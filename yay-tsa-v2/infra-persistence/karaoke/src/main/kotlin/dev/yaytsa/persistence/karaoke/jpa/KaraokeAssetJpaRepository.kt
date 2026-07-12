package dev.yaytsa.persistence.karaoke.jpa

import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
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
            WHERE EXISTS (
                SELECT 1 FROM core_v2_library.entity_genres eg
                JOIN core_v2_library.genres g ON g.id = eg.genre_id
                WHERE eg.entity_id = a.track_id AND lower(g.name) IN ('audiobook', 'audiobooks'))
            """,
        nativeQuery = true,
    )
    fun findAudiobookAssets(): List<KaraokeAssetEntity>
}
