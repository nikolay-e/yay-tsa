package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface LibraryEntityRepository : JpaRepository<LibraryEntityJpa, UUID> {
    @Query(
        "SELECT e FROM LibraryEntityJpa e WHERE e.entityType = :entityType " +
            "ORDER BY COALESCE(e.sortName, e.name)",
    )
    fun findByEntityTypeOrderBySortName(entityType: String): List<LibraryEntityJpa>

    @Query(
        "SELECT e FROM LibraryEntityJpa e WHERE e.entityType = :entityType " +
            "ORDER BY COALESCE(e.sortName, e.name)",
    )
    fun findByEntityTypeOrderBySortNamePaged(
        entityType: String,
        org: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    // Honors the Pageable's Sort (unlike the *OrderBySortName* variants which hardcode it),
    // so callers can sort by sortName / createdAt in either direction.
    fun findByEntityType(
        entityType: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE core_v2_library.f_unaccent(lower(e.name)) ILIKE core_v2_library.f_unaccent(lower(:pattern)) ESCAPE '\\' " +
                "AND e.entity_type = :entityType ORDER BY COALESCE(e.sort_name, e.name)",
        nativeQuery = true,
    )
    fun searchByNameAndType(
        pattern: String,
        entityType: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    fun findAllByIdIn(ids: Collection<UUID>): List<LibraryEntityJpa>

    fun findBySourcePath(sourcePath: String): LibraryEntityJpa?

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = :entityType " +
                "AND lower(e.source_path COLLATE \"und-x-icu\") = lower(:sourcePath COLLATE \"und-x-icu\") " +
                "LIMIT 1",
        nativeQuery = true,
    )
    fun findBySourcePathCaseInsensitive(
        sourcePath: String,
        entityType: String,
    ): LibraryEntityJpa?

    @Query(
        value = "SELECT * FROM core_v2_library.entities WHERE entity_type = :entityType ORDER BY random() LIMIT :limit",
        nativeQuery = true,
    )
    fun findRandomByEntityType(
        entityType: String,
        limit: Int,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND e.source_path LIKE :suffix " +
                "ORDER BY length(e.source_path) ASC LIMIT 1",
        nativeQuery = true,
    )
    fun findTrackBySourcePathSuffix(suffix: String): LibraryEntityJpa?

    @Query(
        value =
            "SELECT e.* FROM core_v2_library.entities e " +
                "JOIN core_v2_library.entity_genres eg ON eg.entity_id = e.id " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE e.entity_type = 'TRACK' AND lower(g.name) = lower(:genre) " +
                "ORDER BY COALESCE(e.sort_name, e.name), e.id " +
                "LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findTracksByGenrePaged(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT e.* FROM core_v2_library.entities e " +
                "JOIN core_v2_library.audio_tracks t ON t.entity_id = e.id " +
                "WHERE e.entity_type = 'TRACK' " +
                "AND (CAST(:fromYear AS integer) IS NULL OR t.year >= :fromYear) " +
                "AND (CAST(:toYear AS integer) IS NULL OR t.year <= :toYear) " +
                "AND (CAST(:genre AS text) IS NULL OR EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) = lower(CAST(:genre AS text)))) " +
                "ORDER BY random() LIMIT :limit",
        nativeQuery = true,
    )
    fun findRandomTracksFiltered(
        genre: String?,
        fromYear: Int?,
        toYear: Int?,
        limit: Int,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT EXISTS (SELECT 1 FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND library_root IS NULL)",
        nativeQuery = true,
    )
    fun existsTrackWithNullLibraryRoot(): Boolean

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND e.size_bytes = :sizeBytes " +
                "AND e.mtime >= :mtimeLow AND e.mtime < :mtimeHigh " +
                "LIMIT 20",
        nativeQuery = true,
    )
    fun findTracksBySizeAndMtimeWithin(
        sizeBytes: Long,
        mtimeLow: java.time.OffsetDateTime,
        mtimeHigh: java.time.OffsetDateTime,
    ): List<LibraryEntityJpa>

    // Audiobooks (genre=Audiobook) are excluded: vocal/instrumental separation of spoken word
    // is pointless and separation is the most expensive per-track job in the system. The
    // entity_genres anti-join matches ALL of a track's genres, mirroring findTracksExcludingGenres.
    @Query(
        value =
            "SELECT e.id FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN ('audiobook', 'audiobooks')) " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_karaoke.assets a WHERE a.track_id = e.id " +
                "AND (a.ready_at IS NOT NULL OR a.fail_count >= :maxFailures)) " +
                "ORDER BY e.created_at, e.id LIMIT :limit",
        nativeQuery = true,
    )
    fun findKaraokeUnprocessedTrackIds(
        maxFailures: Int,
        limit: Int,
    ): List<UUID>

    // ML retry semantics differ from karaoke: failures leave no row behind, so every
    // still-unprocessed track is retried each cycle. Keyset pagination (id > :afterId)
    // lets the worker walk all unprocessed tracks once per cycle in bounded batches
    // without persistently-failing tracks pinning the batch window.
    // Audiobooks (genre=Audiobook) are excluded: CLAP/MERT/MusicNN/Discogs embeddings of
    // spoken-word chapters have no music value (affinity, taste profile, and every
    // recommendation surface already drop audiobooks), and skipping them at the source keeps
    // audiobook tracks out of the embedding-similarity pools that seed radio and the LLM-DJ.
    @Query(
        value =
            "SELECT e.id FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND e.id > :afterId AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN ('audiobook', 'audiobooks')) " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_ml.track_features f WHERE f.track_id = e.id) " +
                "ORDER BY e.id LIMIT :limit",
        nativeQuery = true,
    )
    fun findMlUnprocessedTrackIds(
        afterId: UUID,
        limit: Int,
    ): List<UUID>

    // Subset of :ids that are TRACKs not tagged with any of :excludedGenres. The entity_genres
    // anti-join matches ALL of a track's genres, so a track tagged Audiobook is dropped even when
    // it also carries another genre — mirroring findTracksExcludingGenres. Scoped to :ids so the
    // caller (LLM-DJ seed/suggestion guard) pays only for the handful of ids it is checking.
    @Query(
        value =
            "SELECT e.id FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND e.id IN (:ids) AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN (:excludedGenres))",
        nativeQuery = true,
    )
    fun findTrackIdsInExcludingGenres(
        ids: Collection<UUID>,
        excludedGenres: Collection<String>,
    ): List<UUID>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ARTIST' AND e.name = :name " +
                "AND EXISTS (SELECT 1 FROM core_v2_library.artists a WHERE a.entity_id = e.id) " +
                "ORDER BY COALESCE(e.sort_name, e.name), e.id LIMIT 1",
        nativeQuery = true,
    )
    fun findFirstArtistEntityByExactName(name: String): LibraryEntityJpa?

    fun countByEntityType(entityType: String): Long

    // Page tracks whose genre set is disjoint from :excludedGenres. NOT EXISTS over entity_genres
    // matches ALL of a track's genres (not just the primary), so a track tagged Audiobook is
    // dropped even when it also carries another genre. :descending flips between two fixed orderings
    // (no string interpolation into SQL); id is the stable OFFSET tie-breaker, mirroring browseTracks.
    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN (:excludedGenres)) " +
                "ORDER BY " +
                "CASE WHEN :descending THEN COALESCE(e.sort_name, e.name) END DESC, " +
                "CASE WHEN NOT :descending THEN COALESCE(e.sort_name, e.name) END ASC, " +
                "CASE WHEN :descending THEN e.id END DESC, " +
                "CASE WHEN NOT :descending THEN e.id END ASC " +
                "LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findTracksExcludingGenres(
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
        descending: Boolean,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT count(*) FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN (:excludedGenres))",
        nativeQuery = true,
    )
    fun countTracksExcludingGenres(excludedGenres: Collection<String>): Long

    // Keep an album only if it has at least one track whose genres are disjoint from :excludedGenres.
    // A purely-audiobook album (every track tagged Audiobook) has no such track and is dropped.
    // :byCreated picks created_at vs COALESCE(sort_name, name); :descending flips the direction.
    // Both are constant across rows (no SQL string interpolation); id is the stable OFFSET
    // tie-breaker, mirroring browseAlbums.
    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ALBUM' AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres))) " +
                "ORDER BY " +
                "CASE WHEN :byCreated AND :descending THEN e.created_at END DESC, " +
                "CASE WHEN :byCreated AND NOT :descending THEN e.created_at END ASC, " +
                "CASE WHEN NOT :byCreated AND :descending THEN COALESCE(e.sort_name, e.name) END DESC, " +
                "CASE WHEN NOT :byCreated AND NOT :descending THEN COALESCE(e.sort_name, e.name) END ASC, " +
                "CASE WHEN :descending THEN e.id END DESC, " +
                "CASE WHEN NOT :descending THEN e.id END ASC " +
                "LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findAlbumsExcludingGenres(
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
        byCreated: Boolean,
        descending: Boolean,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT count(*) FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ALBUM' AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres)))",
        nativeQuery = true,
    )
    fun countAlbumsExcludingGenres(excludedGenres: Collection<String>): Long

    // Keep an artist only if it has at least one track whose genres are disjoint from :excludedGenres.
    // :byCreated / :descending select the sort column and direction, mirroring findAlbumsExcludingGenres.
    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ARTIST' AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_artist_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres))) " +
                "ORDER BY " +
                "CASE WHEN :byCreated AND :descending THEN e.created_at END DESC, " +
                "CASE WHEN :byCreated AND NOT :descending THEN e.created_at END ASC, " +
                "CASE WHEN NOT :byCreated AND :descending THEN COALESCE(e.sort_name, e.name) END DESC, " +
                "CASE WHEN NOT :byCreated AND NOT :descending THEN COALESCE(e.sort_name, e.name) END ASC, " +
                "CASE WHEN :descending THEN e.id END DESC, " +
                "CASE WHEN NOT :descending THEN e.id END ASC " +
                "LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findArtistsExcludingGenres(
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
        byCreated: Boolean,
        descending: Boolean,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT count(*) FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ARTIST' AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_artist_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres)))",
        nativeQuery = true,
    )
    fun countArtistsExcludingGenres(excludedGenres: Collection<String>): Long

    @Query(
        value =
            "SELECT count(*) FROM core_v2_library.entities e " +
                "WHERE core_v2_library.f_unaccent(lower(e.name)) ILIKE core_v2_library.f_unaccent(lower(:pattern)) ESCAPE '\\' " +
                "AND e.entity_type = :entityType",
        nativeQuery = true,
    )
    fun countByNameAndType(
        pattern: String,
        entityType: String,
    ): Long

    // Name search variants that also drop excluded genres, mirroring the browse-exclusion predicates:
    // a track is dropped if it has an excluded genre; an album/artist is kept only if it has >=1 track
    // whose genres are disjoint from :excludedGenres.
    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' " +
                "AND core_v2_library.f_unaccent(lower(e.name)) ILIKE core_v2_library.f_unaccent(lower(:pattern)) ESCAPE '\\' " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = e.id AND lower(g.name) IN (:excludedGenres)) " +
                "ORDER BY COALESCE(e.sort_name, e.name) LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchTracksExcludingGenres(
        pattern: String,
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ALBUM' " +
                "AND core_v2_library.f_unaccent(lower(e.name)) ILIKE core_v2_library.f_unaccent(lower(:pattern)) ESCAPE '\\' " +
                "AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres))) " +
                "ORDER BY COALESCE(e.sort_name, e.name) LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchAlbumsExcludingGenres(
        pattern: String,
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'ARTIST' " +
                "AND core_v2_library.f_unaccent(lower(e.name)) ILIKE core_v2_library.f_unaccent(lower(:pattern)) ESCAPE '\\' " +
                "AND EXISTS (" +
                "SELECT 1 FROM core_v2_library.audio_tracks t " +
                "WHERE t.album_artist_id = e.id AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "WHERE eg.entity_id = t.entity_id AND lower(g.name) IN (:excludedGenres))) " +
                "ORDER BY COALESCE(e.sort_name, e.name) LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchArtistsExcludingGenres(
        pattern: String,
        excludedGenres: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<LibraryEntityJpa>

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.entity_type = 'ARTIST'
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.albums a WHERE a.artist_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_artist_id = e.id)
            """,
        nativeQuery = true,
    )
    fun deleteOrphanArtists(): Int

    @Query(
        value =
            "SELECT id FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND library_root = :libraryRoot",
        nativeQuery = true,
    )
    fun findTrackIdsByLibraryRoot(libraryRoot: String): List<UUID>

    @Query(
        value =
            "SELECT id, source_path FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND library_root = :libraryRoot",
        nativeQuery = true,
    )
    fun findTrackIdSourcePathsByLibraryRoot(libraryRoot: String): List<Array<Any?>>

    /**
     * Candidate set for the vanished-track sweep: rows owned by this root PLUS legacy
     * NULL-library_root ghost rows (v1 ETL / pre-column scans) that the per-root query would
     * otherwise never see. The caller deletes any whose source_path is absent from disk.
     */
    @Query(
        value =
            "SELECT id, source_path FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND (library_root = :libraryRoot OR library_root IS NULL)",
        nativeQuery = true,
    )
    fun findTrackIdSourcePathsByLibraryRootOrNull(libraryRoot: String): List<Array<Any?>>

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.audio_tracks t
            WHERE t.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteAudioTracksByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entity_genres g
            WHERE g.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteEntityGenresByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.images i
            WHERE i.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteImagesByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteEntitiesByIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.albums a
            WHERE NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_id = a.entity_id)
            """,
        nativeQuery = true,
    )
    fun deleteAlbumRowsWithoutTracks(): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.entity_type = 'ALBUM'
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_id = e.id)
            """,
        nativeQuery = true,
    )
    fun deleteOrphanAlbums(): Int
}
