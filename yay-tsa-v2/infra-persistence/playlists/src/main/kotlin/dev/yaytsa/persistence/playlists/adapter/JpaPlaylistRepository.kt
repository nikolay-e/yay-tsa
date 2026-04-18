package dev.yaytsa.persistence.playlists.adapter

import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.persistence.playlists.jpa.PlaylistJpaRepository
import dev.yaytsa.persistence.playlists.jpa.PlaylistTrackJpaRepository
import dev.yaytsa.persistence.playlists.mapper.toDomain
import dev.yaytsa.persistence.playlists.mapper.toEntity
import dev.yaytsa.persistence.playlists.mapper.toTrackEntities
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class JpaPlaylistRepository(
    private val playlistJpa: PlaylistJpaRepository,
    private val trackJpa: PlaylistTrackJpaRepository,
    private val entityManager: EntityManager,
) : PlaylistRepository {
    @Transactional(readOnly = true)
    override fun find(playlistId: PlaylistId): PlaylistAggregate? {
        val entity = playlistJpa.findById(playlistId.value).orElse(null) ?: return null
        val tracks = trackJpa.findByPlaylistIdOrderByPosition(playlistId.value)
        return toDomain(entity, tracks)
    }

    override fun save(aggregate: PlaylistAggregate) {
        val entity = aggregate.toEntity()
        val isNew = !playlistJpa.existsById(entity.id)

        if (isNew) {
            entityManager.persist(entity)
        } else {
            val updatedRows =
                entityManager
                    .createQuery(
                        """
                        UPDATE PlaylistEntity p SET
                            p.owner = :owner,
                            p.name = :name,
                            p.description = :description,
                            p.isPublic = :isPublic,
                            p.createdAt = :createdAt,
                            p.updatedAt = :updatedAt,
                            p.version = :nextVersion
                        WHERE p.id = :id AND p.version = :expectedVersion
                        """.trimIndent(),
                    ).setParameter("owner", entity.owner)
                    .setParameter("name", entity.name)
                    .setParameter("description", entity.description)
                    .setParameter("isPublic", entity.isPublic)
                    .setParameter("createdAt", entity.createdAt)
                    .setParameter("updatedAt", entity.updatedAt)
                    .setParameter("nextVersion", entity.version)
                    .setParameter("id", entity.id)
                    .setParameter("expectedVersion", aggregate.version.value - 1)
                    .executeUpdate()

            if (updatedRows == 0) {
                throw OptimisticLockException(
                    "Playlist ${aggregate.id.value} was modified concurrently (expected version ${aggregate.version.value - 1})",
                )
            }

            entityManager.clear()
        }

        // Replace tracks: delete existing, flush, then insert new ones
        trackJpa.deleteByPlaylistId(aggregate.id.value)
        trackJpa.flush()
        trackJpa.saveAll(aggregate.toTrackEntities())
    }

    override fun delete(playlistId: PlaylistId) {
        trackJpa.deleteByPlaylistId(playlistId.value)
        trackJpa.flush()
        playlistJpa.deleteById(playlistId.value)
    }

    @Transactional(readOnly = true)
    override fun findByOwner(userId: UserId): List<PlaylistAggregate> {
        val entities = playlistJpa.findByOwner(userId.value)
        return entities.map { entity ->
            val tracks = trackJpa.findByPlaylistIdOrderByPosition(entity.id)
            toDomain(entity, tracks)
        }
    }
}
