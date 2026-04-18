package dev.yaytsa.application.shared.port

/**
 * Outbox for domain notifications. Enqueued within the same transaction as the aggregate save.
 * A separate poller reads and publishes after commit.
 */
interface OutboxPort {
    fun enqueue(notification: DomainNotification)
}

sealed interface DomainNotification {
    val context: String

    data class PlaybackStateChanged(
        val userId: String,
        val sessionId: String,
    ) : DomainNotification {
        override val context = "playback"
    }

    data class PlaylistChanged(
        val playlistId: String,
    ) : DomainNotification {
        override val context = "playlists"
    }

    data class PreferencesChanged(
        val userId: String,
    ) : DomainNotification {
        override val context = "preferences"
    }

    data class LibraryChanged(
        val entityId: String,
    ) : DomainNotification {
        override val context = "library"
    }

    data class AuthChanged(
        val userId: String,
    ) : DomainNotification {
        override val context = "auth"
    }

    data class AdaptiveQueueChanged(
        val sessionId: String,
    ) : DomainNotification {
        override val context = "adaptive"
    }
}
