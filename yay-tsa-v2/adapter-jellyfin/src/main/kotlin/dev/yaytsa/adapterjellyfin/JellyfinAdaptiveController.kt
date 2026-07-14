package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.HttpFailureTranslator
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.toJellyfinBaseItem
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.recommendation.RadioStationService
import dev.yaytsa.application.recommendation.RecommendationService
import dev.yaytsa.application.recommendation.SemanticTrackSearchService
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
    private val recommendationService: RecommendationService,
    private val semanticTrackSearch: SemanticTrackSearchService,
    private val radioStationService: RadioStationService,
    private val clock: Clock,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val failureTranslator: HttpFailureTranslator,
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
            is CommandResult.Success -> {
                // isRadioMode is a derived property (seed present), not a stored aggregate field —
                // persisting it would create a second source of one truth (manifesto §6). `degraded`
                // is an honest, additive hint so the client never silently presents a best-effort
                // shuffle (cold/obscure seed) as if it were real similarity radio.
                val seed = request.seed_track_id?.let { EntityId(it) }
                val degraded = seed?.let { radioStationService.classifyDegraded(uid, it) }
                if (seed != null && degraded == RadioStationService.DEGRADED_NO_EMBEDDING) {
                    logger.info("{\"src\":\"radio\",\"event\":\"cold_seed\",\"trackId\":\"${seed.value}\",\"reason\":\"no_embedding\"}")
                }
                ResponseEntity.ok(
                    mapOf(
                        "id" to sessionId.value,
                        "userId" to uid.value,
                        "startedAt" to clock.now().toString(),
                        "isRadioMode" to (seed != null),
                        "degraded" to degraded,
                    ),
                )
            }
            is CommandResult.Failed -> failureTranslator.translate(result.failure)
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
        return when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
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
        return when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
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
        val sid = ListeningSessionId(sessionId)
        val aggregate = adaptiveSessionRepo.find(sid) ?: return ResponseEntity.notFound().build()
        val entries = adaptiveQuery.getQueueEntries(sid)
        val uid = UserId(principal.name)
        val seedTrackId = aggregate.seedTrackId

        // Endless radio is client-driven: the backend has no playback position (played_at is never
        // written), so the playing client calls this near the end of its local queue and we APPEND a
        // fresh, diversified tail rather than no-op'ing on a non-empty queue (the old behaviour, which
        // made radio run dry after one bootstrap whenever the LLM was off — the production default).
        // Non-radio sessions keep the old no-op so only seeded radio auto-extends.
        if (entries.isNotEmpty()) {
            if (seedTrackId == null || entries.size >= MAX_RADIO_QUEUE) return ResponseEntity.noContent().build()
            val station =
                radioStationService.build(
                    userId = uid,
                    seedTrackId = seedTrackId,
                    excludeTrackIds = entries.map { it.trackId.value }.toSet(),
                    targetSize = RADIO_EXTEND_BATCH,
                )
            if (station.tracks.isEmpty()) return ResponseEntity.noContent().build()
            val extendCmd =
                RewriteQueueTail(
                    sessionId = sid,
                    baseQueueVersion = aggregate.queueVersion,
                    keepFromPosition = entries.size,
                    newTail = station.tracks.toQueueEntries("ml-extend"),
                )
            val ctx = ctxFactory.create(uid, aggregate.version)
            return when (val result = adaptiveUseCases.execute(extendCmd, ctx)) {
                is CommandResult.Success -> ResponseEntity.noContent().build()
                is CommandResult.Failed -> failureTranslator.empty(result.failure)
            }
        }

        // Empty-queue bootstrap: fill the station deterministically NOW (LLM, if ever enabled, takes
        // over on its next cycle). The seed itself leads, then a varied, de-album-locked tail.
        if (seedTrackId == null) return ResponseEntity.noContent().build()
        val station =
            radioStationService.build(
                userId = uid,
                seedTrackId = seedTrackId,
                excludeTrackIds = emptySet(),
                targetSize = RADIO_STATION_SIZE,
            )
        val newTail =
            buildList {
                add(
                    NewQueueEntry(
                        id = AdaptiveQueueEntryId(UUID.randomUUID().toString()),
                        trackId = TrackId(seedTrackId.value),
                        addedReason = "seed-track",
                        intentLabel = "radio",
                    ),
                )
                addAll(station.tracks.filter { it.trackId != seedTrackId.value }.toQueueEntries("radio"))
            }
        val cmd =
            RewriteQueueTail(
                sessionId = sid,
                baseQueueVersion = aggregate.queueVersion,
                keepFromPosition = 0,
                newTail = newTail,
            )
        val ctx = ctxFactory.create(uid, aggregate.version)
        return when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
    }

    private fun List<RadioStationService.StationTrack>.toQueueEntries(intentLabel: String): List<NewQueueEntry> =
        map {
            NewQueueEntry(
                id = AdaptiveQueueEntryId(UUID.randomUUID().toString()),
                trackId = TrackId(it.trackId),
                addedReason = it.reason,
                intentLabel = intentLabel,
            )
        }

    // Typed body (not Map<String, Any?>): an untyped map makes springdoc emit an
    // unconstrained object schema, so fuzzers legitimately send junk that only fails
    // at the DB (signal_type is varchar(30)) — the spec must carry the real domain.
    data class SignalRequest(
        @JsonProperty("track_id") @JsonAlias("trackId") val trackId: String,
        @JsonProperty("signal_type") @JsonAlias("signalType") val signalType: String? = null,
        val context: Any? = null,
    )

    @PostMapping("/sessions/{sessionId}/signals")
    fun recordSignal(
        @PathVariable sessionId: String,
        @RequestBody body: SignalRequest,
        principal: Principal,
    ): ResponseEntity<Void> {
        sessionAccessFailure<Void>(sessionId, principal)?.let { return it }
        val uid = UserId(principal.name)
        val cmd =
            RecordPlaybackSignal(
                ListeningSessionId(sessionId),
                UUID.randomUUID().toString(),
                TrackId(body.trackId),
                null,
                body.signalType ?: "UNKNOWN",
                body.context?.toString(),
            )
        val ctx = ctxFactory.create(uid, currentSessionVersion(sessionId))
        return when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
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
        return when (val result = preferencesUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> failureTranslator.empty(result.failure)
        }
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
        val seeds = toSeedDtos(recommendationService.radioSeedTracks(UserId(uid), limit = 8))
        return ResponseEntity.ok(mapOf("seeds" to seeds, "available" to seeds.isNotEmpty()))
    }

    @GetMapping("/recommend/daily-mix")
    fun getDailyMix(
        @RequestParam(defaultValue = "30") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val tracks = recommendationService.dailyMixTracks(userId, limit.coerceIn(1, 100))
        return recommendationResponse(userId, tracks)
    }

    @GetMapping("/recommend/discover")
    fun getDiscover(
        @RequestParam(defaultValue = "30") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val tracks = recommendationService.discoveryTracks(userId, limit.coerceIn(1, 100))
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
        val lookups = TrackLookups.load(tracks, libraryQueries)
        val items = tracks.map { it.toJellyfinBaseItem(favTrackIds, lookups) }
        return ResponseEntity.ok(mapOf("Items" to items, "TotalRecordCount" to items.size))
    }

    @GetMapping("/recommend/search")
    fun searchRecommendations(
        @RequestParam("q") query: String,
        @RequestParam(defaultValue = "20") limit: Int,
        principal: Principal?,
    ): ResponseEntity<List<RecommendedTrackDto>> {
        val uid = principal?.name ?: return ResponseEntity.status(401).build()
        val userId = UserId(uid)
        val tracks = semanticTrackSearch.searchWithLexicalFallback(userId, query, limit.coerceIn(1, 100))
        val results = toSeedDtos(tracks).map { it.copy(source = "semantic", score = 0.0) }
        return ResponseEntity.ok(results)
    }

    data class RecommendedTrackDto(
        val trackId: String,
        val name: String,
        val artistId: String?,
        val artistName: String?,
        val albumId: String?,
        val albumName: String?,
        val imageTag: String?,
        val source: String? = null,
        val score: Double? = null,
    )

    private fun toSeedDtos(tracks: List<dev.yaytsa.domain.library.Track>): List<RecommendedTrackDto> {
        val lookups = TrackLookups.load(tracks, libraryQueries)
        return tracks.map { track ->
            RecommendedTrackDto(
                trackId = track.id.value,
                name = track.name,
                artistId = track.albumArtistId?.value,
                artistName = track.albumArtistId?.let { lookups.artistNames[it] },
                albumId = track.albumId?.value,
                albumName = track.albumId?.let { lookups.albumNames[it] },
                imageTag = track.albumId?.value,
            )
        }
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(JellyfinAdaptiveController::class.java)

        // Initial radio station length on bootstrap (seed + ~40 varied tracks).
        private const val RADIO_STATION_SIZE = 40

        // Tracks appended on each client-driven near-end extension.
        private const val RADIO_EXTEND_BATCH = 20

        // Safety cap so a very long radio session's backend queue doesn't grow without bound
        // (the client keeps only a bounded local window; this just stops server-side accumulation).
        private const val MAX_RADIO_QUEUE = 300
    }
}
