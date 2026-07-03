package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.application.playback.port.TrackPlayCount
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(readOnly = true)
class JpaPlayHistoryQueryPort(
    private val jpa: PlayHistoryJpaRepository,
) : PlayHistoryQueryPort {
    override fun mostPlayedTrackCounts(
        userId: UserId,
        limit: Int,
    ): List<TrackPlayCount> =
        jpa
            .findMostPlayedItemCountsByUser(userId.value, maxOf(limit, 1))
            .map { TrackPlayCount(TrackId(it.getItemId()), it.getPlayCount()) }

    override fun recentlyPlayedTrackIds(
        userId: UserId,
        limit: Int,
    ): List<TrackId> = jpa.findRecentlyPlayedItemIdsByUser(userId.value, maxOf(limit, 1)).map { TrackId(it) }

    override fun playCountsByTrackIds(trackIds: Collection<TrackId>): Map<TrackId, Long> {
        if (trackIds.isEmpty()) return emptyMap()
        return jpa
            .countPlaysByItemIds(trackIds.map { it.value })
            .associate { TrackId(it.getItemId()) to it.getPlayCount() }
    }
}
