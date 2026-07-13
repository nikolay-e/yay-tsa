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
        return musicSurfaceFilter.filter(matches, userId).take(limit)
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
}
