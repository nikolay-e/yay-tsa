package dev.yaytsa.application.playback

import java.time.Instant

data class SavedPlayQueue(
    val userId: String,
    val trackIds: List<String>,
    val currentTrackId: String?,
    val positionMs: Long,
    val changedAt: Instant,
    val changedBy: String?,
)
