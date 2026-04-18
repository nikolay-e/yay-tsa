package dev.yaytsa.persistence.playlists.mapper

import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistEntry
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.persistence.playlists.entity.PlaylistEntity
import dev.yaytsa.persistence.playlists.entity.PlaylistTrackEntity
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

fun toDomain(
    entity: PlaylistEntity,
    tracks: List<PlaylistTrackEntity>,
): PlaylistAggregate =
    PlaylistAggregate(
        id = PlaylistId(entity.id),
        owner = UserId(entity.owner),
        name = entity.name,
        description = entity.description,
        isPublic = entity.isPublic,
        tracks = tracks.sortedBy { it.position }.map { it.toDomain() },
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = AggregateVersion(entity.version),
    )

fun PlaylistTrackEntity.toDomain(): PlaylistEntry =
    PlaylistEntry(
        trackId = TrackId(trackId),
        addedAt = addedAt,
    )

fun PlaylistAggregate.toEntity(): PlaylistEntity =
    PlaylistEntity(
        id = id.value,
        owner = owner.value,
        name = name,
        description = description,
        isPublic = isPublic,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version.value,
    )

fun PlaylistAggregate.toTrackEntities(): List<PlaylistTrackEntity> =
    tracks.mapIndexed { index, entry ->
        PlaylistTrackEntity(
            playlistId = id.value,
            position = index,
            trackId = entry.trackId.value,
            addedAt = entry.addedAt,
        )
    }
