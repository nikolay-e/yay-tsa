package dev.yaytsa.persistence.library

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
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.UUID

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
        return LibraryMappers.toAlbum(entity, album, imagePath)
    }

    override fun getArtist(artistId: EntityId): Artist? {
        val id = UUID.fromString(artistId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        val artist = artistRepo.findById(id).orElse(null) ?: return null
        val imagePath = imageRepo.findByEntityIdAndIsPrimaryTrue(id)?.path
        return LibraryMappers.toArtist(entity, artist, imagePath)
    }

    override fun browseArtists(
        limit: Int,
        offset: Int,
    ): List<Artist> {
        val safeLimit = maxOf(limit, 1)
        val page = PageRequest.of(offset / safeLimit, safeLimit)
        val entities =
            entityRepo.findByEntityTypeOrderBySortNamePaged(
                EntityType.ARTIST.name,
                page,
            )
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
    ): List<Album> {
        val safeLimit = maxOf(limit, 1)
        val page = PageRequest.of(offset / safeLimit, safeLimit)
        val entities =
            entityRepo.findByEntityTypeOrderBySortNamePaged(
                EntityType.ALBUM.name,
                page,
            )
        val albumIds = entities.map { it.id }
        val albums = albumRepo.findAllById(albumIds).associateBy { it.entityId }
        val primaryImages = findPrimaryImages(albumIds)
        return entities.mapNotNull { entity ->
            val album = albums[entity.id] ?: return@mapNotNull null
            LibraryMappers.toAlbum(entity, album, primaryImages[entity.id])
        }
    }

    override fun browseAlbumsByArtist(artistId: EntityId): List<Album> {
        val id = UUID.fromString(artistId.value)
        val albumJpas = albumRepo.findByArtistId(id)
        val entityIds = albumJpas.map { it.entityId }
        val entities = entityRepo.findAllById(entityIds).associateBy { it.id }
        val primaryImages = findPrimaryImages(entityIds)
        return albumJpas
            .mapNotNull { album ->
                val entity = entities[album.entityId] ?: return@mapNotNull null
                LibraryMappers.toAlbum(entity, album, primaryImages[album.entityId])
            }.sortedBy { it.releaseDate }
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

    override fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults {
        val pattern = "%$query%"
        val page = PageRequest.of(offset / maxOf(limit, 1), maxOf(limit, 1))

        val artistEntities = entityRepo.searchByNameAndType(pattern, EntityType.ARTIST.name, page)
        val albumEntities = entityRepo.searchByNameAndType(pattern, EntityType.ALBUM.name, page)
        val trackEntities = entityRepo.searchByNameAndType(pattern, EntityType.TRACK.name, page)

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

    override fun resolveTrackFilePath(trackId: EntityId): String? {
        val id = UUID.fromString(trackId.value)
        val entity = entityRepo.findById(id).orElse(null) ?: return null
        if (entity.entityType != EntityType.TRACK.name) return null
        return entity.sourcePath
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
