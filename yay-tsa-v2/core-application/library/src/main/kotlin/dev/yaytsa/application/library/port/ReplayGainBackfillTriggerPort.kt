package dev.yaytsa.application.library.port

interface ReplayGainBackfillTriggerPort {
    fun triggerBackfill(): Boolean
}
