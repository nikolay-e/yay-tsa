package dev.yaytsa.application.library.port

data class ScanStatus(
    val scanning: Boolean,
    val lastCompletedAt: String?,
    val lastTrackCount: Int?,
)

interface LibraryScanTriggerPort {
    fun triggerScan(): Boolean

    fun status(): ScanStatus
}
