package dev.yaytsa.persistence.library.mapper

import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Genre
import dev.yaytsa.domain.library.Image
import dev.yaytsa.domain.library.Track
import dev.yaytsa.persistence.library.entity.AlbumJpa
import dev.yaytsa.persistence.library.entity.ArtistJpa
import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import dev.yaytsa.persistence.library.entity.GenreJpa
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.GenreId
import dev.yaytsa.shared.ImageId

object LibraryMappers {
    fun toTrack(
        entity: LibraryEntityJpa,
        track: AudioTrackJpa,
        primaryImagePath: String?,
        primaryGenreName: String?,
    ): Track =
        Track(
            id = EntityId(entity.id.toString()),
            name = entity.name ?: "",
            sortName = entity.sortName,
            parentId = entity.parentId?.let { EntityId(it.toString()) },
            albumId = track.albumId?.let { EntityId(it.toString()) },
            albumArtistId = track.albumArtistId?.let { EntityId(it.toString()) },
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            durationMs = track.durationMs,
            bitrate = track.bitrate,
            sampleRate = track.sampleRate,
            channels = track.channels,
            year = track.year,
            codec = track.codec,
            genre = primaryGenreName,
            coverImagePath = primaryImagePath,
        )

    fun toAlbum(
        entity: LibraryEntityJpa,
        album: AlbumJpa,
        primaryImagePath: String?,
    ): Album =
        Album(
            id = EntityId(entity.id.toString()),
            name = entity.name ?: "",
            sortName = entity.sortName,
            parentId = entity.parentId?.let { EntityId(it.toString()) },
            artistId = album.artistId?.let { EntityId(it.toString()) },
            releaseDate = album.releaseDate,
            totalTracks = album.totalTracks,
            totalDiscs = album.totalDiscs,
            coverImagePath = primaryImagePath,
        )

    fun toArtist(
        entity: LibraryEntityJpa,
        artist: ArtistJpa,
        primaryImagePath: String?,
    ): Artist =
        Artist(
            id = EntityId(entity.id.toString()),
            name = entity.name ?: "",
            sortName = entity.sortName,
            parentId = entity.parentId?.let { EntityId(it.toString()) },
            musicbrainzId = artist.musicbrainzId,
            biography = artist.biography,
            coverImagePath = primaryImagePath,
        )

    fun toGenre(genre: GenreJpa): Genre =
        Genre(
            id = GenreId(genre.id.toString()),
            name = genre.name,
        )

    fun toImage(image: ImageJpa): Image =
        Image(
            id = ImageId(image.id.toString()),
            entityId = EntityId(image.entityId.toString()),
            imageType = image.imageType,
            path = image.path,
            isPrimary = image.isPrimary,
        )
}
