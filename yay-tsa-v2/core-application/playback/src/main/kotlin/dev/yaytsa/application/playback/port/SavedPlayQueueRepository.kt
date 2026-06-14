package dev.yaytsa.application.playback.port

import dev.yaytsa.application.playback.SavedPlayQueue
import dev.yaytsa.shared.UserId

interface SavedPlayQueueRepository {
    fun find(userId: UserId): SavedPlayQueue?

    fun save(queue: SavedPlayQueue)
}
