package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.adaptershared.toJellyfinBaseItem
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.EndListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.NewQueueEntry
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.domain.adaptive.RewriteQueueTail
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.adaptive.UpdateSessionContext
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/v1")
class JellyfinAdaptiveController(
    private val adaptiveUseCases: AdaptiveUseCases,
    private val adaptiveQuery: AdaptiveQueryPort,
    private val adaptiveSessionRepo: AdaptiveSessionRepository,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val libraryQueries: LibraryQueries,
    private val mlQuery: MlQueryPort,
    private val embeddingPort: dev.yaytsa.application.ml.port.EmbeddingPort,
    private val musicSurfaceFilter: dev.yaytsa.application.recommendation.MusicSurfaceFilter,
    private val clock: Clock,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    data class StartSessionRequest(
        val userId: String = "",
        val state: SessionStateDto? = null,
        val seed_track_id: String? = null,
    )

    data class SessionStateDto(
        val energy: Float? = null,
        val intensity: Float? = null,
        val moodTags: List<String> = emptyList(),
        val attentionMode: String = "active",
        val constraints: List<String> = emptyList(),
    )

    @PostMapping("/sessions")
    fun startSession(
        @RequestBody request: StartSessionRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        if (request.userId.isNotBlank() && request.userId != principal.name) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val uid = UserId(principal.name)
        val sessionId = ListeningSessionId(UUID.randomUUID().toString())
        val cmd =
            StartListeningSession(
                sessionId,
                request.state?.attentionMode ?: "active",
                request.seed_track_id?.let { EntityId(it) },
                emptyList(),
            )
        val ctx = ctxFactory.create(uid, AggregateVersion.INITIAL)
        return when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success ->
                ResponseEntity.ok(
                    mapOf(
                        "id" to sessionId.value,
                        "userId" to uid.value,
                        "startedAt" to clock.now().toString(),
                        "isRadioMode" to false,
                    ),
                )
            is CommandResult.Failed -> problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", result.failure.toString())
        }
    }

    @PatchMapping("/sessions/{sessionId}/state")
    fun updateState(
        @PathVariable sessionId: String,
        @RequestBody state: SessionStateDto,
        principal: Principal,
    ): ResponseEntity<Void> {
        sessionAccessFailure<Void>(sessionId, principal)?.let { return it }
        val uid = UserId(principal.name)
        val cmd = UpdateSessionContext(ListeningSessionId(sessionId), state.energy, state.intensity, state.moodTags, state.attentionMode)
        val ctx = ctxFactory.create(uid, currentSessionVersion(sessionId))
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun endSession(
        @PathVariable sessionId: String,
        principal: Principal,
    ): ResponseEntity<Void> {
        sessionAccessFailure<Void>(sessionId, principal)?.let { return it }
        val uid = UserId(principal.name)
        val cmd = EndListeningSession(ListeningSessionId(sessionId), null)
        val ctx = ctxFactory.create(uid, currentSessionVersion(sessionId))
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    private fun <T> sessionAccessFailure(
        sessionId: String,
        principal: Principal,
    ): ResponseEntity<T>? {
        val session = adaptiveQuery.findSession(ListeningSessionId(sessionId)) ?: return ResponseEntity.notFound().build()
        if (session.userId.value != principal.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    /**
     * Read the session's current OCC version so [adaptiveUseCases.execute]
     * passes the OCC check. Without this every update/signal/end is sent with
     * [AggregateVersion.INITIAL] (0), the loaded snapshot has version ≥ 1,
     * the handler returns [Failure.Conflict], and the controller silently
     * swallows it (returns 204 without persisting the signal).
     * Falls back to INITIAL for a fresh session (handled by StartListeningSession).
     */
    private fun currentSessionVersion(sessionId: String): AggregateVersion =
        adaptiveSessionRepo.find(ListeningSessionId(sessionId))?.version ?: AggregateVersion.INITIAL

    @GetMapping("/sessions/{sessionId}/queue")
    fun getQueue(
        @PathVariable sessionId: String,
        principal: Principal,
    ): ResponseEntity<Any> {
        sessionAccessFailure<Any>(sessionId, principal)?.let { return it }
        val entries = adaptiveQuery.getQueueEntries(ListeningSessionId(sessionId))
        return ResponseEntity.ok(mapOf("tracks" to entries))
    }

    @PostMapping("/sessions/{sessionId}/queue/refresh")
    fun refreshQueue(
        @PathVariable sessionId: String,
        principal: Principal,
    ): ResponseEntity<Void> {
        sessionAccessFailure<Void>(sessionId, principal)?.let { return it }
        // Empty-queue bootstrap: when a Radio seed launches a session, the LLM scheduler
        // doesn't run for another ~30s. Fill the queue deterministically NOW with
        // (seed + recommended tracks) so the user gets immediate playback. The LLM
        // takes over on its next cycle.
        val sid = ListeningSessionId(sessionId)
        val aggregate = adaptiveSessionRepo.find(sid) ?: return ResponseEntity.notFound().build()
        val entries = adaptiveQuery.getQueueEntries(sid)
        if (entries.isNotEmpty()) return ResponseEntity.noContent().build()

        val uid = UserId(principal.name)
        val seedTrackId = aggregate.seedTrackId
        val similarTracks =
            seedTrackId
                ?.let { mlQuery.findSimilarTracks(TrackId(it.value), REFRESH_QUEUE_BOOTSTRAP_SIZE * 2) }
                .orEmpty()
                .mapNotNull { libraryQueries.getTrack(EntityId(it.value)) }
                .let { tracks -> musicSurfaceFilter.filterRedLines(tracks, uid) }
                .take(REFRESH_QUEUE_BOOTSTRAP_SIZE)
        val orderedTrackIds = mutableListOf<String>()
        val reasonByTrack = mutableMapOf<String, String>()
        seedTrackId?.value?.let {
            orderedTrackIds += it
            reasonByTrack[it] = "seed-track"
        }
        similarTracks.forEach { track ->
            if (track.id.value !in orderedTrackIds) {
                orderedTrackIds += track.id.value
                reasonByTrack[track.id.value] = "bootstrap-similarity"
            }
        }
        if (orderedTrackIds.size < REFRESH_QUEUE_BOOTSTRAP_SIZE + 1) {
            val recommended = buildRecommendedTracks(uid, limit = REFRESH_QUEUE_BOOTSTRAP_SIZE)
            for (rec in recommended) {
                val rid = rec["trackId"] as? String ?: continue
                if (rid !in orderedTrackIds) {
                    orderedTrackIds += rid
                    reasonByTrack[rid] = "bootstrap-affinity"
                }
            }
        }
        if (orderedTrackIds.isEmpty()) return ResponseEntity.noContent().build()

        val cmd =
            RewriteQueueTail(
                sessionId = sid,
                baseQueueVersion = aggregate.queueVersion,
                keepFromPosition = 0,
                newTail =
                    orderedTrackIds.map { trackId ->
                        NewQueueEntry(
                            id = AdaptiveQueueEntryId(UUID.randomUUID().toString()),
                            trackId = TrackId(trackId),
                            addedReason = reasonByTrack[trackId] ?: "bootstrap-affinity",
                            intentLabel = "bootstrap",
                        )
                    },
            )
        val ctx = ctxFactory.create(uid, aggregate.version)
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/sessions/{sessionId}/signals")
    fun recordSignal(
        @PathVariable sessionId: String,
        @RequestBody body: Map<String, Any?>,
        principal: Principal,
    ): ResponseEntity<Void> {
        sessionAccessFailure<Void>(sessionId, principal)?.let { return it }
        val uid = UserId(principal.name)
        val cmd =
            RecordPlaybackSignal(
                ListeningSessionId(sessionId),
                UUID.randomUUID().toString(),
                TrackId(body["track_id"] as? String ?: return ResponseEntity.badRequest().build()),
                null,
                body["signal_type"] as? String ?: "UNKNOWN",
                body["context"]?.toString(),
            )
        val ctx = ctxFactory.create(uid, currentSessionVersion(sessionId))
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/sessions/active")
    fun getActiveSession(principal: Principal): ResponseEntity<Any> {
        val session =
            adaptiveQuery.findActiveSession(UserId(principal.name))
                ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(session)
    }

    @GetMapping("/users/{userId}/preferences")
    fun getPreferences(
        @PathVariable userId: String,
        principal: Principal,
    ): ResponseEntity<Any> {
        if (principal.name != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val prefs =
            preferencesQueries.find(UserId(userId))
                ?: return ResponseEntity.ok(
                    mapOf(
                        "hardRules" to "{}",
                        "softPrefs" to "{}",
                        "djStyle" to "{}",
                        "redLines" to emptyList<String>(),
                    ),
                )
        val contract = prefs.preferenceContract
        return ResponseEntity.ok(
            mapOf(
                "hardRules" to (contract?.hardRules ?: "{}"),
                "softPrefs" to (contract?.softPrefs ?: "{}"),
                "djStyle" to (contract?.djStyle ?: "{}"),
                "redLines" to (contract?.redLines?.split(",")?.filter { it.isNotBlank() } ?: emptyList<String>()),
            ),
        )
    }

    @PutMapping("/users/{userId}/preferences")
    fun updatePreferences(
        @PathVariable userId: String,
        @RequestBody body: Map<String, Any?>,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (principal.name != userId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val uid = UserId(userId)
        val prefs = preferencesQueries.find(uid) ?: UserPreferencesAggregate.empty(uid)
        val cmd =
            UpdatePreferenceContract(
                uid,
                normalizeJsonField(body["hardRules"]),
                normalizeJsonField(body["softPrefs"]),
                normalizeJsonField(body["djStyle"]),
                normalizeRedLines(body["redLines"]),
                clock.now(),
            )
        val ctx = ctxFactory.create(uid, prefs.version)
        preferencesUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    // Frontend may send the JSON object inline OR as a pre-stringified blob. Map<String,?> .toString()
    // emits Java's "{key=val}" syntax which is not JSON — re-serialise via Jackson.
    private fun normalizeJsonField(value: Any?): String =
        when (value) {
            null -> ""
            is String -> value
            else -> objectMapper.writeValueAsString(value)
        }

    // redLines is a list of strings on the wire and a comma-separated string in storage.
    // Map<>.toString() on a list returns "[a, b]" — corrupts on round-trip.
    private fun normalizeRedLines(value: Any?): String =
        when (value) {
            null -> ""
            is String -> value
            is List<*> -> value.filterNotNull().joinToString(",") { it.toString() }
            else -> value.toString()
        }

    @GetMapping("/recommend/radio/seeds")
    fun getRadioSeeds(principal: Principal?): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val seeds = buildRecommendedSeeds(UserId(uid), limit = 8)
        return ResponseEntity.ok(mapOf("seeds" to seeds, "available" to seeds.isNotEmpty()))
    }

    @GetMapping("/recommend/daily-mix")
    fun getDailyMix(
        @RequestParam(defaultValue = "30") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val tracks = pickRecommendedTracks(userId, limit.coerceIn(1, 100))
        return recommendationResponse(userId, tracks)
    }

    @GetMapping("/recommend/discover")
    fun getDiscover(
        @RequestParam(defaultValue = "30") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val tracks = pickDiscoveryTracks(userId, limit.coerceIn(1, 100))
        return recommendationResponse(userId, tracks)
    }

    private fun recommendationResponse(
        userId: UserId,
        tracks: List<dev.yaytsa.domain.library.Track>,
    ): ResponseEntity<Any> {
        val favTrackIds =
            preferencesQueries
                .find(userId)
                ?.favorites
                .orEmpty()
                .map { it.trackId.value }
                .toSet()
        val lookups = trackLookups(tracks)
        val items = tracks.map { it.toJellyfinBaseItem(favTrackIds, lookups) }
        return ResponseEntity.ok(mapOf("Items" to items, "TotalRecordCount" to items.size))
    }

    @GetMapping("/recommend/search")
    fun searchRecommendations(
        @RequestParam("q") query: String,
        @RequestParam(defaultValue = "20") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val cappedLimit = limit.coerceIn(1, 100)
        val tracks = semanticOrLexicalSearch(userId, query, cappedLimit)
        val results = tracks.map { trackToSeedMap(it) + mapOf("source" to "semantic", "score" to 0.0) }
        return ResponseEntity.ok(results)
    }

    private fun semanticOrLexicalSearch(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val vector = embeddingPort.encodeText(query)
        val semanticTracks =
            if (vector != null) {
                mlQuery
                    .findTracksByClapVector(vector, limit * 2)
                    .mapNotNull { libraryQueries.getTrack(EntityId(it.value)) }
            } else {
                emptyList()
            }
        val filtered = musicSurfaceFilter.filter(semanticTracks, userId).take(limit)
        if (filtered.isNotEmpty()) return filtered
        // Degrade to lexical search when the embedding service is disabled/unreachable or
        // no track carries a CLAP embedding yet — better UX than an empty result.
        return musicSurfaceFilter.filter(libraryQueries.searchText(query, limit, 0).tracks, userId).take(limit)
    }

    private fun buildRecommendedTracks(
        userId: UserId,
        limit: Int,
    ): List<Map<String, Any?>> = pickRecommendedTracks(userId, limit).map { trackToSeedMap(it) }

    /**
     * Shuffled sample of top user-track affinities (so the Daily Mix actually changes between
     * refreshes), then favorites (cold-start for new users), then a varied sample across the
     * library. Result is deduped by trackId.
     */
    private fun pickRecommendedTracks(
        userId: UserId,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val candidates = collectRecommendationCandidates(userId, limit)
        return musicSurfaceFilter.filter(candidates, userId).take(limit)
    }

    private fun collectRecommendationCandidates(
        userId: UserId,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<dev.yaytsa.domain.library.Track>()
        // Over-pull so the red-line filter has slack before we take(limit).
        val targetPool = limit * 2

        val affinityPool = (limit * AFFINITY_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
        mlQuery.getTopAffinities(userId, affinityPool).shuffled().forEach { aff ->
            if (out.size >= targetPool) return@forEach
            val track = libraryQueries.getTrack(EntityId(aff.trackId.value)) ?: return@forEach
            if (seen.add(track.id.value)) out.add(track)
        }

        if (out.size < targetPool) {
            preferencesQueries
                .find(userId)
                ?.favorites
                .orEmpty()
                .shuffled()
                .forEach { fav ->
                    if (out.size >= targetPool) return@forEach
                    val track = libraryQueries.getTrack(EntityId(fav.trackId.value)) ?: return@forEach
                    if (seen.add(track.id.value)) out.add(track)
                }
        }

        if (out.size < targetPool) {
            libraryQueries.browseTracksRandom(targetPool - out.size).forEach { track ->
                if (out.size >= targetPool) return@forEach
                if (seen.add(track.id.value)) out.add(track)
            }
        }

        return out
    }

    /**
     * Discovery is the inverse of Daily Mix: instead of resurfacing tracks the user already likes,
     * it surfaces tracks *adjacent* to their taste that they have not heard. We build a "heard" set
     * (top affinities + favorites), seed kNN search from the user's taste-cluster medoids (the real
     * facets of their taste), collect similar tracks, drop anything in the heard set, then apply the
     * same red-line + audiobook filtering as Daily Mix. Cold start falls back to a random library
     * sample minus heard tracks. Shuffled so each refresh surfaces a fresh slice.
     */
    private fun pickDiscoveryTracks(
        userId: UserId,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val heard = collectHeardTrackIds(userId, limit)
        val candidates = collectDiscoveryCandidates(userId, limit, heard)
        return musicSurfaceFilter
            .filter(candidates, userId)
            .shuffled()
            .take(limit)
    }

    private fun collectHeardTrackIds(
        userId: UserId,
        limit: Int,
    ): Set<String> {
        val heardPool = (limit * HEARD_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
        val heard = mutableSetOf<String>()
        mlQuery.getTopAffinities(userId, heardPool).forEach { heard.add(it.trackId.value) }
        preferencesQueries
            .find(userId)
            ?.favorites
            .orEmpty()
            .forEach { heard.add(it.trackId.value) }
        return heard
    }

    private fun collectDiscoveryCandidates(
        userId: UserId,
        limit: Int,
        heard: Set<String>,
    ): List<dev.yaytsa.domain.library.Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<dev.yaytsa.domain.library.Track>()
        val targetPool = limit * 2

        val seeds =
            mlQuery
                .getTasteClusterRepresentatives(userId)
                .ifEmpty {
                    mlQuery.getTopAffinities(userId, DISCOVERY_SEED_FALLBACK_POOL).map { it.trackId }
                }

        for (seed in seeds) {
            if (out.size >= targetPool) break
            mlQuery.findSimilarTracks(seed, DISCOVERY_SIMILARITY_K).forEach { candidateId ->
                if (out.size >= targetPool) return@forEach
                val id = candidateId.value
                if (id in heard || id in seen) return@forEach
                val track = libraryQueries.getTrack(EntityId(id)) ?: return@forEach
                if (seen.add(id)) out.add(track)
            }
        }

        if (out.size < targetPool) {
            libraryQueries.browseTracksRandom((targetPool - out.size) * 2).forEach { track ->
                if (out.size >= targetPool) return@forEach
                val id = track.id.value
                if (id in heard || id in seen) return@forEach
                if (seen.add(id)) out.add(track)
            }
        }

        return out
    }

    private fun trackLookups(tracks: List<dev.yaytsa.domain.library.Track>): TrackLookups {
        if (tracks.isEmpty()) return TrackLookups()
        val albumIds = tracks.mapNotNull { it.albumId }.toSet()
        val artistIds = tracks.mapNotNull { it.albumArtistId }.toSet()
        return TrackLookups(
            albumNames = libraryQueries.getEntityNamesByIds(albumIds),
            artistNames = libraryQueries.getEntityNamesByIds(artistIds),
        )
    }

    /**
     * Radio seeds = one station per artist. We pull a wide pool of top affinities,
     * collapse to the highest-affinity track per albumArtistId, shuffle the resulting
     * artists, and take [limit]. Fallback to favorites and random library so the row
     * isn't empty for cold-start users.
     */
    private fun buildRecommendedSeeds(
        userId: UserId,
        limit: Int,
    ): List<Map<String, Any?>> {
        val redLineTerms = musicSurfaceFilter.loadRedLineTerms(userId)
        val perArtist = LinkedHashMap<String, dev.yaytsa.domain.library.Track>()
        val seenTracks = mutableSetOf<String>()

        fun blocked(track: dev.yaytsa.domain.library.Track): Boolean = musicSurfaceFilter.isBlocked(track, redLineTerms)

        // Primary: the representative track (medoid) of each of the user's taste clusters —
        // one seed per real taste facet (metal / russian-rap / folk-metal / …), so radio
        // spans the whole taste instead of a single averaged centroid. Falls through to
        // affinity/favorites/random below when the user has no clusters yet (cold start).
        mlQuery.getTasteClusterRepresentatives(userId).forEach { tid ->
            val track = libraryQueries.getTrack(EntityId(tid.value)) ?: return@forEach
            if (blocked(track)) return@forEach
            val artistKey = track.albumArtistId?.value ?: track.id.value
            if (artistKey !in perArtist) perArtist[artistKey] = track
            seenTracks.add(track.id.value)
        }

        if (perArtist.size < limit) {
            val poolSize = (limit * SEED_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
            mlQuery.getTopAffinities(userId, poolSize).forEach { aff ->
                val track = libraryQueries.getTrack(EntityId(aff.trackId.value)) ?: return@forEach
                if (blocked(track)) return@forEach
                val artistKey = track.albumArtistId?.value ?: track.id.value
                if (artistKey !in perArtist) perArtist[artistKey] = track
                seenTracks.add(track.id.value)
            }
        }

        val picked = perArtist.values.toMutableList().also { it.shuffle() }

        if (picked.size < limit) {
            val favorites =
                preferencesQueries
                    .find(userId)
                    ?.favorites
                    .orEmpty()
                    .shuffled()
            for (fav in favorites) {
                if (picked.size >= limit) break
                val track = libraryQueries.getTrack(EntityId(fav.trackId.value)) ?: continue
                if (blocked(track)) continue
                val artistKey = track.albumArtistId?.value ?: track.id.value
                if (perArtist.put(artistKey, track) == null && seenTracks.add(track.id.value)) {
                    picked.add(track)
                }
            }
        }

        if (picked.size < limit) {
            libraryQueries.browseTracksRandom((limit - picked.size) * 2).forEach { track ->
                if (picked.size >= limit) return@forEach
                if (blocked(track)) return@forEach
                val artistKey = track.albumArtistId?.value ?: track.id.value
                if (perArtist.put(artistKey, track) == null && seenTracks.add(track.id.value)) {
                    picked.add(track)
                }
            }
        }

        return picked.take(limit).map { trackToSeedMap(it) }
    }

    private fun trackToSeedMap(track: dev.yaytsa.domain.library.Track): Map<String, Any?> {
        val albumName =
            track.albumId?.let { libraryQueries.getEntityNamesByIds(setOf(it))[it] }
        val artistName =
            track.albumArtistId?.let { libraryQueries.getEntityNamesByIds(setOf(it))[it] }
        return mapOf(
            "trackId" to track.id.value,
            "name" to track.name,
            "artistId" to track.albumArtistId?.value,
            "artistName" to artistName,
            "albumId" to track.albumId?.value,
            "albumName" to albumName,
            "imageTag" to track.albumId?.value,
        )
    }

    companion object {
        private const val REFRESH_QUEUE_BOOTSTRAP_SIZE = 10

        // Larger pool than [limit] so .shuffled() gives the user a fresh slice on each refresh
        // instead of the deterministic top-N by affinity_score.
        private const val AFFINITY_POOL_MULTIPLIER = 5

        // Radio seeds collapse to one-per-artist, so we need a wider pool than [limit] to find
        // [limit] distinct artists even when a user's affinities cluster around a few favorites.
        private const val SEED_POOL_MULTIPLIER = 20

        // Discovery excludes everything the user already knows. Pull a deep affinity history so the
        // "heard" set is wide enough that kNN neighbours are genuinely new, not recently-played.
        private const val HEARD_POOL_MULTIPLIER = 20

        // When the user has no taste clusters yet, seed discovery from their strongest affinities.
        private const val DISCOVERY_SEED_FALLBACK_POOL = 25

        // Neighbours pulled per seed; over-pulled so the heard/red-line/audiobook filters have slack.
        private const val DISCOVERY_SIMILARITY_K = 25
    }
}
