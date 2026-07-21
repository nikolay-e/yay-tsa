package dev.yaytsa.application.recommendation

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.EmbeddingPort
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId

class SemanticTrackSearchService(
    private val embeddingPort: EmbeddingPort,
    private val mlQuery: MlQueryPort,
    private val libraryQueries: LibraryQueries,
    private val musicSurfaceFilter: MusicSurfaceFilter,
) {
    fun semanticSearch(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<Track> {
        val vector = embeddingPort.encodeText(query) ?: return emptyList()
        // Over-pull so the red-line/audiobook filter has slack before we take(limit).
        val matches = libraryQueries.getTracksByIds(mlQuery.findTracksByClapVector(vector, limit * 2).map { EntityId(it.value) })
        return musicSurfaceFilter
            .filter(matches, userId)
            // Spoken-word interludes and intro snippets carry degenerate CLAP embeddings that rank
            // absurdly high for mood queries ("dark atmospheric doom" returning narrated skits).
            // No spoken/interlude metadata exists, so duration is the honest proxy: a vibe query
            // wants songs, and real songs in this library start above a minute.
            .filter { (it.durationMs ?: Long.MAX_VALUE) >= SEMANTIC_MIN_TRACK_DURATION_MS }
            .take(limit)
    }

    // Degrade to lexical search when the embedding service is disabled/unreachable or
    // no track carries a CLAP embedding yet — better UX than an empty result.
    fun searchWithLexicalFallback(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<Track> {
        val semantic = semanticSearch(userId, query, limit)
        if (semantic.isNotEmpty()) return semantic
        return musicSurfaceFilter.filter(libraryQueries.searchText(query, limit, 0).tracks, userId).take(limit)
    }

    companion object {
        const val SEMANTIC_MIN_TRACK_DURATION_MS = 60_000L
    }
}
