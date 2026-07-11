package dev.yaytsa.application.ml.port

interface EmbeddingPort {
    fun encodeText(query: String): FloatArray?

    // Whether the text-embedding backend is configured and reachable. Lets callers tell a user that
    // vibe/semantic search is unavailable instead of silently degrading to a name match that can
    // never satisfy a mood query.
    fun isAvailable(): Boolean = false
}
