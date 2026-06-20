package dev.yaytsa.application.ml.port

interface EmbeddingPort {
    fun encodeText(query: String): FloatArray?
}
