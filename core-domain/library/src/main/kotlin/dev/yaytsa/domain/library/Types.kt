package dev.yaytsa.domain.library

import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.GenreId
import dev.yaytsa.shared.ImageId
import java.time.LocalDate

enum class EntityType { TRACK, ALBUM, ARTIST, FOLDER }

sealed interface LibraryEntity {
    val id: EntityId
    val name: String
    val sortName: String?
    val parentId: EntityId?
}

data class Track(
    override val id: EntityId,
    override val name: String,
    override val sortName: String?,
    override val parentId: EntityId?,
    val albumId: EntityId?,
    val albumArtistId: EntityId?,
    val trackNumber: Int?,
    val discNumber: Int,
    val durationMs: Long?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val year: Int?,
    val codec: String?,
    val genre: String?,
    val coverImagePath: String?,
) : LibraryEntity

data class Album(
    override val id: EntityId,
    override val name: String,
    override val sortName: String?,
    override val parentId: EntityId?,
    val artistId: EntityId?,
    val releaseDate: LocalDate?,
    val totalTracks: Int?,
    val totalDiscs: Int,
    val coverImagePath: String?,
) : LibraryEntity

data class Artist(
    override val id: EntityId,
    override val name: String,
    override val sortName: String?,
    override val parentId: EntityId?,
    val musicbrainzId: String?,
    val biography: String?,
    val coverImagePath: String?,
) : LibraryEntity

data class Genre(
    val id: GenreId,
    val name: String,
)

data class Image(
    val id: ImageId,
    val entityId: EntityId,
    val imageType: String,
    val path: String,
    val isPrimary: Boolean,
)

data class SearchResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
)
