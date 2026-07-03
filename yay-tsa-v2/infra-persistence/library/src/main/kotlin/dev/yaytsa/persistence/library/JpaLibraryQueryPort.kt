package dev.yaytsa.persistence.library

import dev.yaytsa.application.library.port.GenreStatistics
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.EntityType
import dev.yaytsa.domain.library.Genre
import dev.yaytsa.domain.library.Image
import dev.yaytsa.domain.library.SearchResults
import dev.yaytsa.domain.library.Track
import dev.yaytsa.persistence.library.mapper.LibraryMappers
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.EntityGenreRepository
import dev.yaytsa.persistence.library.repository.GenreRepository
import dev.yaytsa.persistence.library.repository.ImageRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.util.UUID

private fun escapeLikePattern(query: String): String =
    "%" +
        query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") +
        "%"

@Repository
class JpaLibraryQueryPort(
    private val entityRepo: LibraryEntityRepository,
    private val artistRepo: ArtistRepository,
    private val albumRepo: AlbumRepository,
    private val trackRepo: AudioTrackRepository,
    private val genreRepo: GenreRepository,
    private val entityGenreRepo: EntityGenreRepository,
    private val imageRepo: ImageRepository,
) : LibraryQueryPort {
    override fun getTrack(trackId: EntityId): Track? {
        val id = UUID.fromString(trackId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        val track = trackRepo.findById(id).orElse(null) ?: return null
        val imagePath = imageRepo.findByEntityIdAndIsPrimaryTrue(id)?.path
        val genreName = findPrimaryGenreName(id)
        return LibraryMappers.toTrack(entity, track, imagePath, genreName)
    }

    override fun getAlbum(albumId: EntityId): Album? {
        val id = UUID.fromString(albumId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        val album = albumRepo.findById(id).orElse(null) ?: return null
        val imagePath = imageRepo.findByEntityIdAndIsPrimaryTrue(id)?.path
        return LibraryMappers.toAlbum(entity, album, imagePath, trackRepo.findMinYearByAlbumId(id))
    }

    override fun getArtist(artistId: EntityId): Artist? {
        val id = UUID.fromString(artistId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        val artist = artistRepo.findById(id).orElse(null) ?: return null
        val imagePath = imageRepo.findByEntityIdAndIsPrimaryTrue(id)?.path
        return LibraryMappers.toArtist(entity, artist, imagePath)
    }

    override fun getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String> {
        if (ids.isEmpty()) return emptyMap()
        val uuids = ids.map { UUID.fromString(it.value) }
        return entityRepo
            .findAllByIdIn(uuids)
            .mapNotNull { e -> e.name?.let { EntityId(e.id.toString()) to it } }
            .toMap()
    }

    // Shared sort resolution for entity browsing. Mirrors browseTracks: DateCreated → created_at,
    // anything else → sort_name; id is the stable OFFSET tie-breaker since neither column is a
    // total order. All three entity types (TRACK/ALBUM/ARTIST) live in the same entities table,
    // so the same Sort applies uniformly.
    private fun browsePageable(
        offset: Int,
        limit: Int,
        sortBy: String,
        sortOrder: String,
    ): OffsetBasedPageRequest {
        val property = if (sortBy == "DateCreated") "createdAt" else "sortName"
        val direction = if (sortOrder.equals("Descending", ignoreCase = true)) Sort.Direction.DESC else Sort.Direction.ASC
        return OffsetBasedPageRequest(
            maxOf(offset, 0).toLong(),
            maxOf(limit, 1),
            Sort.by(direction, property).and(Sort.by(direction, "id")),
        )
    }

    override fun browseArtists(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Artist> {
        val entities = entityRepo.findByEntityType(EntityType.ARTIST.name, browsePageable(offset, limit, sortBy, sortOrder))
        val artistIds = entities.map { it.id }
        val artists = artistRepo.findAllById(artistIds).associateBy { it.entityId }
        val primaryImages = findPrimaryImages(artistIds)
        return entities.mapNotNull { entity ->
            val artist = artists[entity.id] ?: return@mapNotNull null
            LibraryMappers.toArtist(entity, artist, primaryImages[entity.id])
        }
    }

    override fun browseAlbums(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Album> {
        val entities = entityRepo.findByEntityType(EntityType.ALBUM.name, browsePageable(offset, limit, sortBy, sortOrder))
        return assembleAlbumsFromEntities(entities)
    }

    override fun browseAlbumsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Album> {
        if (excludedGenreNames.isEmpty()) return browseAlbums(limit, offset, sortBy, sortOrder)
        val lowered = excludedGenreNames.map { it.lowercase() }
        val byCreated = sortBy == "DateCreated"
        val descending = sortOrder.equals("Descending", ignoreCase = true)
        return assembleAlbumsFromEntities(
            entityRepo.findAlbumsExcludingGenres(lowered, maxOf(limit, 1), maxOf(offset, 0), byCreated, descending),
        )
    }

    override fun countAlbumsExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return countAlbums()
        return entityRepo.countAlbumsExcludingGenres(excludedGenreNames.map { it.lowercase() }).toInt()
    }

    override fun browseArtistsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Artist> {
        if (excludedGenreNames.isEmpty()) return browseArtists(limit, offset, sortBy, sortOrder)
        val lowered = excludedGenreNames.map { it.lowercase() }
        val byCreated = sortBy == "DateCreated"
        val descending = sortOrder.equals("Descending", ignoreCase = true)
        val entities = entityRepo.findArtistsExcludingGenres(lowered, maxOf(limit, 1), maxOf(offset, 0), byCreated, descending)
        val artistIds = entities.map { it.id }
        val artists = artistRepo.findAllById(artistIds).associateBy { it.entityId }
        val primaryImages = findPrimaryImages(artistIds)
        return entities.mapNotNull { entity ->
            val artist = artists[entity.id] ?: return@mapNotNull null
            LibraryMappers.toArtist(entity, artist, primaryImages[entity.id])
        }
    }

    override fun countArtistsExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return countArtists()
        return entityRepo.countArtistsExcludingGenres(excludedGenreNames.map { it.lowercase() }).toInt()
    }

    override fun browseAlbumsByCreatedDesc(
        limit: Int,
        offset: Int,
    ): List<Album> {
        val pageable =
            OffsetBasedPageRequest(
                maxOf(offset, 0).toLong(),
                maxOf(limit, 1),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")),
            )
        return assembleAlbumsFromEntities(entityRepo.findByEntityType(EntityType.ALBUM.name, pageable))
    }

    override fun browseAlbumsRandom(limit: Int): List<Album> =
        assembleAlbumsFromEntities(entityRepo.findRandomByEntityType(EntityType.ALBUM.name, maxOf(limit, 1)))

    override fun browseAlbumsByYearRange(
        fromYear: Int,
        toYear: Int,
        limit: Int,
        offset: Int,
    ): List<Album> {
        val direction = if (fromYear <= toYear) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable =
            OffsetBasedPageRequest(
                maxOf(offset, 0).toLong(),
                maxOf(limit, 1),
                Sort.by(direction, "releaseDate").and(Sort.by(Sort.Direction.ASC, "entityId")),
            )
        val albumJpas =
            albumRepo.findByReleaseDateBetween(
                java.time.LocalDate.of(minOf(fromYear, toYear), 1, 1),
                java.time.LocalDate.of(maxOf(fromYear, toYear), 12, 31),
                pageable,
            )
        return assembleAlbumsFromJpas(albumJpas)
    }

    override fun browseAlbumsByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Album> {
        val trackEntityIds = entityGenreRepo.findTrackEntityIdsByGenreNames(listOf(genre.lowercase()))
        if (trackEntityIds.isEmpty()) return emptyList()
        val albumIds = trackRepo.findAllByEntityIdIn(trackEntityIds).mapNotNull { it.albumId }.distinct()
        if (albumIds.isEmpty()) return emptyList()
        return assembleAlbumsFromJpas(albumRepo.findAllById(albumIds))
            .sortedBy { (it.sortName ?: it.name).lowercase() }
            .drop(maxOf(offset, 0))
            .take(maxOf(limit, 1))
    }

    override fun browseAlbumsByArtist(artistId: EntityId): List<Album> {
        val id = UUID.fromString(artistId.value)
        return assembleAlbumsFromJpas(albumRepo.findByArtistId(id)).sortedBy { it.releaseDate }
    }

    private fun assembleAlbumsFromEntities(entities: List<dev.yaytsa.persistence.library.entity.LibraryEntityJpa>): List<Album> {
        if (entities.isEmpty()) return emptyList()
        val albumIds = entities.map { it.id }
        val albums = albumRepo.findAllById(albumIds).associateBy { it.entityId }
        val primaryImages = findPrimaryImages(albumIds)
        return entities.mapNotNull { entity ->
            val album = albums[entity.id] ?: return@mapNotNull null
            LibraryMappers.toAlbum(entity, album, primaryImages[entity.id])
        }
    }

    private fun assembleAlbumsFromJpas(albumJpas: List<dev.yaytsa.persistence.library.entity.AlbumJpa>): List<Album> {
        if (albumJpas.isEmpty()) return emptyList()
        val entityIds = albumJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        return albumJpas.mapNotNull { album ->
            val entity = entities[album.entityId] ?: return@mapNotNull null
            LibraryMappers.toAlbum(entity, album, primaryImages[album.entityId])
        }
    }

    override fun browseTracksByAlbum(albumId: EntityId): List<Track> {
        val id = UUID.fromString(albumId.value)
        val trackJpas = trackRepo.findByAlbumId(id)
        val entityIds = trackJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        val genreNames = findPrimaryGenreNames(entityIds)
        return trackJpas
            .mapNotNull { track ->
                val entity = entities[track.entityId] ?: return@mapNotNull null
                LibraryMappers.toTrack(entity, track, primaryImages[track.entityId], genreNames[track.entityId])
            }.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
    }

    override fun browseTracksByArtist(
        artistId: EntityId,
        limit: Int,
        offset: Int,
    ): List<Track> {
        val id = UUID.fromString(artistId.value)
        val trackJpas = trackRepo.findByAlbumArtistIdPaged(id, maxOf(limit, 1), maxOf(offset, 0))
        return assembleTracksInOrder(trackJpas)
    }

    override fun countTracksByArtist(artistId: EntityId): Int = trackRepo.countByAlbumArtistId(UUID.fromString(artistId.value)).toInt()

    override fun getTracksByIds(trackIds: List<EntityId>): List<Track> {
        if (trackIds.isEmpty()) return emptyList()
        val uuids = trackIds.map { UUID.fromString(it.value) }
        val byEntityId = trackRepo.findAllByEntityIdIn(uuids).associateBy { it.entityId }
        // Preserve caller-supplied order (favorites/playlist ordering matters).
        val orderedJpas = uuids.mapNotNull { byEntityId[it] }
        return assembleTracksInOrder(orderedJpas)
    }

    override fun getAlbumsByIds(albumIds: List<EntityId>): List<Album> {
        if (albumIds.isEmpty()) return emptyList()
        val uuids = albumIds.map { UUID.fromString(it.value) }
        val byEntityId = albumRepo.findAllById(uuids).associateBy { it.entityId }
        val orderedJpas = uuids.mapNotNull { byEntityId[it] }
        if (orderedJpas.isEmpty()) return emptyList()
        val entityIds = orderedJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        val minYears = trackRepo.findMinYearsByAlbumIds(entityIds).associate { it.getAlbumId() to it.getMinYear() }
        return orderedJpas.mapNotNull { album ->
            val entity = entities[album.entityId] ?: return@mapNotNull null
            LibraryMappers.toAlbum(entity, album, primaryImages[album.entityId], minYears[album.entityId])
        }
    }

    override fun getArtistsByIds(artistIds: List<EntityId>): List<Artist> {
        if (artistIds.isEmpty()) return emptyList()
        val uuids = artistIds.map { UUID.fromString(it.value) }
        val byEntityId = artistRepo.findAllById(uuids).associateBy { it.entityId }
        val orderedJpas = uuids.mapNotNull { byEntityId[it] }
        if (orderedJpas.isEmpty()) return emptyList()
        val entityIds = orderedJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        return orderedJpas.mapNotNull { artist ->
            val entity = entities[artist.entityId] ?: return@mapNotNull null
            LibraryMappers.toArtist(entity, artist, primaryImages[artist.entityId])
        }
    }

    override fun findArtistByName(name: String): Artist? {
        val entity = entityRepo.findFirstArtistEntityByExactName(name) ?: return null
        val artist = artistRepo.findById(entity.id).orElse(null) ?: return null
        val imagePath = imageRepo.findByEntityIdAndIsPrimaryTrue(entity.id)?.path
        return LibraryMappers.toArtist(entity, artist, imagePath)
    }

    override fun browseTracksByGenreNames(genreNames: Collection<String>): List<Track> {
        if (genreNames.isEmpty()) return emptyList()
        val lowered = genreNames.map { it.lowercase() }
        val entityIds = entityGenreRepo.findTrackEntityIdsByGenreNames(lowered)
        if (entityIds.isEmpty()) return emptyList()
        return assembleTracksInOrder(trackRepo.findAllByEntityIdIn(entityIds))
            .sortedBy { (it.sortName ?: it.name).lowercase() }
    }

    private fun assembleTracksInOrder(trackJpas: List<dev.yaytsa.persistence.library.entity.AudioTrackJpa>): List<Track> {
        if (trackJpas.isEmpty()) return emptyList()
        val entityIds = trackJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        val genreNames = findPrimaryGenreNames(entityIds)
        return trackJpas.mapNotNull { track ->
            val entity = entities[track.entityId] ?: return@mapNotNull null
            LibraryMappers.toTrack(entity, track, primaryImages[track.entityId], genreNames[track.entityId])
        }
    }

    override fun browseTracks(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> = assembleTracksFromEntities(entityRepo.findByEntityType(EntityType.TRACK.name, browsePageable(offset, limit, sortBy, sortOrder)))

    private fun assembleTracksFromEntities(entities: List<dev.yaytsa.persistence.library.entity.LibraryEntityJpa>): List<Track> {
        if (entities.isEmpty()) return emptyList()
        val entityIds = entities.map { it.id }
        val tracksByEntityId = trackRepo.findAllById(entityIds).associateBy { it.entityId }
        val primaryImages = findPrimaryImages(entityIds)
        val genreNames = findPrimaryGenreNames(entityIds)
        return entities.mapNotNull { entity ->
            val track = tracksByEntityId[entity.id] ?: return@mapNotNull null
            LibraryMappers.toTrack(entity, track, primaryImages[entity.id], genreNames[entity.id])
        }
    }

    override fun browseTracksExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> {
        if (excludedGenreNames.isEmpty()) return browseTracks(limit, offset, sortBy, sortOrder)
        val lowered = excludedGenreNames.map { it.lowercase() }
        val descending = sortOrder.equals("Descending", ignoreCase = true)
        return assembleTracksFromEntities(entityRepo.findTracksExcludingGenres(lowered, maxOf(limit, 1), maxOf(offset, 0), descending))
    }

    override fun countTracksExcludingGenres(excludedGenreNames: Collection<String>): Int {
        if (excludedGenreNames.isEmpty()) return countTracks()
        return entityRepo.countTracksExcludingGenres(excludedGenreNames.map { it.lowercase() }).toInt()
    }

    override fun browseTracksRandom(limit: Int): List<Track> =
        assembleTracksFromEntities(entityRepo.findRandomByEntityType(EntityType.TRACK.name, maxOf(limit, 1)))

    override fun browseTracksRandomFiltered(
        genre: String?,
        fromYear: Int?,
        toYear: Int?,
        limit: Int,
    ): List<Track> = assembleTracksFromEntities(entityRepo.findRandomTracksFiltered(genre, fromYear, toYear, maxOf(limit, 1)))

    override fun browseTracksByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Track> = assembleTracksFromEntities(entityRepo.findTracksByGenrePaged(genre, maxOf(limit, 1), maxOf(offset, 0)))

    override fun listGenreStatistics(): List<GenreStatistics> =
        genreRepo.findGenreStatistics().map {
            GenreStatistics(name = it.getName(), songCount = it.getSongCount().toInt(), albumCount = it.getAlbumCount().toInt())
        }

    override fun searchText(
        query: String,
        limit: Int,
        offset: Int,
        excludedGenres: Collection<String>,
    ): SearchResults {
        val pattern = escapeLikePattern(query)
        val cappedLimit = maxOf(limit, 1)
        val cappedOffset = maxOf(offset, 0)
        val page = OffsetBasedPageRequest(cappedOffset.toLong(), cappedLimit)

        val artistEntities =
            if (excludedGenres.isEmpty()) {
                entityRepo.searchByNameAndType(pattern, EntityType.ARTIST.name, page)
            } else {
                entityRepo.searchArtistsExcludingGenres(pattern, excludedGenres, cappedLimit, cappedOffset)
            }
        val albumEntities =
            if (excludedGenres.isEmpty()) {
                entityRepo.searchByNameAndType(pattern, EntityType.ALBUM.name, page)
            } else {
                entityRepo.searchAlbumsExcludingGenres(pattern, excludedGenres, cappedLimit, cappedOffset)
            }
        val trackEntities =
            if (excludedGenres.isEmpty()) {
                entityRepo.searchByNameAndType(pattern, EntityType.TRACK.name, page)
            } else {
                entityRepo.searchTracksExcludingGenres(pattern, excludedGenres, cappedLimit, cappedOffset)
            }

        val allIds = (artistEntities + albumEntities + trackEntities).map { it.id }
        val primaryImages = findPrimaryImages(allIds)

        val artists =
            if (artistEntities.isNotEmpty()) {
                val artistMap = artistRepo.findAllById(artistEntities.map { it.id }).associateBy { it.entityId }
                artistEntities.mapNotNull { entity ->
                    val artist = artistMap[entity.id] ?: return@mapNotNull null
                    LibraryMappers.toArtist(entity, artist, primaryImages[entity.id])
                }
            } else {
                emptyList()
            }

        val albums =
            if (albumEntities.isNotEmpty()) {
                val albumMap = albumRepo.findAllById(albumEntities.map { it.id }).associateBy { it.entityId }
                albumEntities.mapNotNull { entity ->
                    val album = albumMap[entity.id] ?: return@mapNotNull null
                    LibraryMappers.toAlbum(entity, album, primaryImages[entity.id])
                }
            } else {
                emptyList()
            }

        val tracks =
            if (trackEntities.isNotEmpty()) {
                val trackMap = trackRepo.findAllById(trackEntities.map { it.id }).associateBy { it.entityId }
                val genreNames = findPrimaryGenreNames(trackEntities.map { it.id })
                trackEntities.mapNotNull { entity ->
                    val track = trackMap[entity.id] ?: return@mapNotNull null
                    LibraryMappers.toTrack(entity, track, primaryImages[entity.id], genreNames[entity.id])
                }
            } else {
                emptyList()
            }

        return SearchResults(artists = artists, albums = albums, tracks = tracks)
    }

    override fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> {
        val uuids = trackIds.map { UUID.fromString(it.value) }
        val found = entityRepo.findAllByIdIn(uuids).filter { it.entityType == EntityType.TRACK.name }
        return found.map { TrackId(it.id.toString()) }.toSet()
    }

    override fun getGenres(entityId: EntityId): List<Genre> {
        val id = UUID.fromString(entityId.value)
        val entityGenres = entityGenreRepo.findByEntityId(id)
        if (entityGenres.isEmpty()) return emptyList()
        val genreIds = entityGenres.map { it.genreId }
        val genres = genreRepo.findAllById(genreIds)
        return genres.map { LibraryMappers.toGenre(it) }
    }

    override fun getPrimaryImage(entityId: EntityId): Image? {
        val id = UUID.fromString(entityId.value)
        val image = imageRepo.findByEntityIdAndIsPrimaryTrue(id) ?: return null
        return LibraryMappers.toImage(image)
    }

    private fun findPrimaryImages(entityIds: List<UUID>): Map<UUID, String> {
        if (entityIds.isEmpty()) return emptyMap()
        return imageRepo
            .findByEntityIdInAndIsPrimaryTrue(entityIds)
            .associate { it.entityId!! to it.path }
    }

    private fun findPrimaryGenreName(entityId: UUID): String? {
        val entityGenres = entityGenreRepo.findByEntityId(entityId)
        if (entityGenres.isEmpty()) return null
        return genreRepo.findById(entityGenres.first().genreId).orElse(null)?.name
    }

    override fun countTracks(): Int = entityRepo.countByEntityType(EntityType.TRACK.name).toInt()

    override fun sumTrackDurationsMs(): Long = trackRepo.sumDurationsMs()

    override fun countAlbums(): Int = entityRepo.countByEntityType(EntityType.ALBUM.name).toInt()

    override fun countArtists(): Int = entityRepo.countByEntityType(EntityType.ARTIST.name).toInt()

    override fun countTextSearchTracks(query: String): Int = entityRepo.countByNameAndType(escapeLikePattern(query), EntityType.TRACK.name).toInt()

    override fun countTextSearchArtists(query: String): Int = entityRepo.countByNameAndType(escapeLikePattern(query), EntityType.ARTIST.name).toInt()

    override fun countTextSearchAlbums(query: String): Int = entityRepo.countByNameAndType(escapeLikePattern(query), EntityType.ALBUM.name).toInt()

    override fun countAlbumsByArtistIds(artistIds: Set<EntityId>): Map<EntityId, Int> {
        if (artistIds.isEmpty()) return emptyMap()
        val uuids = artistIds.map { UUID.fromString(it.value) }
        return albumRepo
            .countAlbumsByArtistIds(uuids)
            .associate { EntityId(it.getArtistId().toString()) to it.getAlbumCount().toInt() }
    }

    override fun resolveTrackFilePath(trackId: EntityId): String? {
        val id = UUID.fromString(trackId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        if (entity.entityType != EntityType.TRACK.name) return null
        val raw = entity.sourcePath ?: return null
        if (raw.startsWith("/")) return raw
        val root = entity.libraryRoot ?: return raw
        return "$root/$raw"
    }

    private fun findPrimaryGenreNames(entityIds: List<UUID>): Map<UUID, String> {
        if (entityIds.isEmpty()) return emptyMap()
        val entityGenres = entityGenreRepo.findByEntityIdIn(entityIds)
        val genreIdsByEntity = entityGenres.groupBy { it.entityId }
        val allGenreIds = entityGenres.map { it.genreId }.distinct()
        val genres = genreRepo.findAllById(allGenreIds).associateBy { it.id }
        return genreIdsByEntity
            .mapNotNull { (entityId, egs) ->
                val genreName = genres[egs.first().genreId]?.name ?: return@mapNotNull null
                entityId to genreName
            }.toMap()
    }
}
