package dev.yaytsa.application.shared.port

interface RemoteCommandPort {
    fun publish(
        userId: String,
        targetDeviceId: String,
        command: String,
        params: Map<String, Any?> = emptyMap(),
    )
}
