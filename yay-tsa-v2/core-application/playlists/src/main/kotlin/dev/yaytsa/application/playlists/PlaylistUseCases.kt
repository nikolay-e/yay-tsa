package dev.yaytsa.application.playlists

import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.application.shared.PayloadFingerprint
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.playlists.PlaylistDeps
import dev.yaytsa.domain.playlists.PlaylistHandler
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.asCommandFailure

class PlaylistUseCases(
    private val playlistRepo: PlaylistRepository,
    private val idempotencyStore: IdempotencyStore,
    private val capabilities: ProtocolCapabilitiesRegistry,
    private val txExecutor: TransactionalCommandExecutor,
    private val outbox: OutboxPort,
    private val trackValidator: (Set<TrackId>) -> Set<TrackId>,
) {
    fun execute(
        cmd: PlaylistCommand,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        if (!capabilities.isCommandSupported(ctx.protocolId, cmd::class)) {
            return Failure.UnsupportedByProtocol(ctx.protocolId, cmd::class.simpleName ?: "unknown").asCommandFailure()
        }

        val commandType = cmd::class.qualifiedName ?: cmd::class.simpleName ?: "unknown"
        val payloadHash = PayloadFingerprint.compute(cmd)

        return txExecutor.execute {
            val existing = idempotencyStore.find(ctx.userId, commandType, ctx.idempotencyKey)
            if (existing != null) {
                if (existing.payloadHash != payloadHash) {
                    return@execute Failure.InvariantViolation("Idempotency key reused with different payload").asCommandFailure()
                }
                val current = playlistRepo.find(cmd.playlistId)
                return@execute if (current != null) {
                    CommandResult.Success(current, AggregateVersion(existing.resultVersion))
                } else if (cmd is DeletePlaylist) {
                    // Playlist was successfully deleted on the original call — return success.
                    // We construct a tombstone aggregate since the entity no longer exists.
                    // Safety: ctx.userId is guaranteed to match the original caller because
                    // idempotencyStore.find is scoped by (userId, commandType, idempotencyKey).
                    CommandResult.Success(
                        PlaylistAggregate(
                            id = cmd.playlistId,
                            owner = ctx.userId,
                            name = "",
                            description = null,
                            isPublic = false,
                            tracks = emptyList(),
                            createdAt = ctx.requestTime,
                            updatedAt = ctx.requestTime,
                            version = AggregateVersion(existing.resultVersion),
                        ),
                        AggregateVersion(existing.resultVersion),
                    )
                } else {
                    Failure.NotFound("Playlist", cmd.playlistId.value).asCommandFailure()
                }
            }

            val snapshot = playlistRepo.find(cmd.playlistId)
            val deps = loadDeps(cmd)
            val result = PlaylistHandler.handle(snapshot, cmd, ctx, deps)
            when (result) {
                is CommandResult.Success -> {
                    if (cmd is DeletePlaylist) {
                        playlistRepo.delete(cmd.playlistId)
                    } else {
                        playlistRepo.save(result.value)
                    }
                    idempotencyStore.store(ctx.userId, commandType, ctx.idempotencyKey, payloadHash, result.newVersion.value)
                    outbox.enqueue(DomainNotification.PlaylistChanged(cmd.playlistId.value))
                }
                is CommandResult.Failed -> {}
            }
            result
        }
    }

    fun find(playlistId: PlaylistId): PlaylistAggregate? = playlistRepo.find(playlistId)

    fun findByOwner(userId: UserId): List<PlaylistAggregate> = playlistRepo.findByOwner(userId)

    private fun loadDeps(cmd: PlaylistCommand): PlaylistDeps {
        val toValidate =
            when (cmd) {
                is AddTracksToPlaylist -> cmd.trackIds.toSet()
                else -> emptySet()
            }
        return PlaylistDeps(knownTrackIds = if (toValidate.isNotEmpty()) trackValidator(toValidate) else emptySet())
    }
}
