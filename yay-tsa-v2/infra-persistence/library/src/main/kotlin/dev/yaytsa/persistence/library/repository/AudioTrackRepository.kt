package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AudioTrackRepository : JpaRepository<AudioTrackJpa, UUID> {
    fun findByAlbumId(albumId: UUID): List<AudioTrackJpa>

    fun findAllByEntityIdIn(entityIds: Collection<UUID>): List<AudioTrackJpa>

    // Paginate an artist's tracks in SQL (grouped by album, then disc/track) instead of
    // loading every album's full track set and slicing in memory.
    @Query(
        value =
            "SELECT t.* FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_artist_id = :artistId " +
                "ORDER BY t.album_id, t.disc_number NULLS FIRST, t.track_number NULLS FIRST, t.entity_id " +
                "LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findByAlbumArtistIdPaged(
        @Param("artistId") artistId: UUID,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<AudioTrackJpa>

    fun countByAlbumArtistId(albumArtistId: UUID): Long
}
