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

    @Query("SELECT COALESCE(SUM(t.durationMs), 0) FROM AudioTrackJpa t")
    fun sumDurationsMs(): Long

    @Query("SELECT MIN(t.year) FROM AudioTrackJpa t WHERE t.albumId = :albumId AND t.year IS NOT NULL")
    fun findMinYearByAlbumId(
        @Param("albumId") albumId: UUID,
    ): Int?

    @Query(
        "SELECT t.albumId AS albumId, MIN(t.year) AS minYear " +
            "FROM AudioTrackJpa t WHERE t.albumId IN :albumIds AND t.year IS NOT NULL GROUP BY t.albumId",
    )
    fun findMinYearsByAlbumIds(
        @Param("albumIds") albumIds: Collection<UUID>,
    ): List<MinYearByAlbum>

    @Query(
        value =
            "SELECT t.entity_id, e.source_path, e.library_root " +
                "FROM core_v2_library.audio_tracks t " +
                "JOIN core_v2_library.entities e ON e.id = t.entity_id " +
                "WHERE t.replaygain_track_gain IS NULL AND t.replaygain_album_gain IS NULL " +
                "AND t.replaygain_track_peak IS NULL AND t.replaygain_checked_at IS NULL " +
                "AND t.entity_id > :afterId " +
                "ORDER BY t.entity_id LIMIT :limit",
        nativeQuery = true,
    )
    fun findReplayGainBackfillCandidates(
        @Param("afterId") afterId: UUID,
        @Param("limit") limit: Int,
    ): List<Array<Any?>>
}

interface MinYearByAlbum {
    fun getAlbumId(): UUID

    fun getMinYear(): Int
}
