package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.EndListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.adaptive.UpdateSessionContext
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
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
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val clock: Clock,
) {
    data class StartSessionRequest(
        val userId: String,
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
    ): ResponseEntity<Any> {
        val uid = UserId(request.userId)
        val sessionId = ListeningSessionId(UUID.randomUUID().toString())
        val cmd =
            StartListeningSession(
                sessionId,
                request.state?.attentionMode ?: "active",
                request.seed_track_id?.let { EntityId(it) },
                emptyList(),
            )
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
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
            is CommandResult.Failed -> ResponseEntity.badRequest().body(mapOf("error" to result.failure.toString()))
        }
    }

    @PatchMapping("/sessions/{sessionId}/state")
    fun updateState(
        @PathVariable sessionId: String,
        @RequestBody state: SessionStateDto,
        principal: Principal,
    ): ResponseEntity<Void> {
        val uid = UserId(principal.name)
        adaptiveQuery.findSession(ListeningSessionId(sessionId)) ?: return ResponseEntity.notFound().build()
        val cmd = UpdateSessionContext(ListeningSessionId(sessionId), state.energy, state.intensity, state.moodTags, state.attentionMode)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun endSession(
        @PathVariable sessionId: String,
        principal: Principal,
    ): ResponseEntity<Void> {
        val uid = UserId(principal.name)
        val cmd = EndListeningSession(ListeningSessionId(sessionId), null)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        adaptiveUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/sessions/{sessionId}/queue")
    fun getQueue(
        @PathVariable sessionId: String,
    ): ResponseEntity<Any> {
        val entries = adaptiveQuery.getQueueEntries(ListeningSessionId(sessionId))
        return ResponseEntity.ok(mapOf("tracks" to entries))
    }

    @PostMapping("/sessions/{sessionId}/queue/refresh")
    fun refreshQueue(
        @PathVariable sessionId: String,
    ): ResponseEntity<Void> {
        // TODO: trigger LLM orchestrator for this session
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/sessions/{sessionId}/signals")
    fun recordSignal(
        @PathVariable sessionId: String,
        @RequestBody body: Map<String, Any?>,
        principal: Principal,
    ): ResponseEntity<Void> {
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
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
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
    ): ResponseEntity<Any> {
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
        val uid = UserId(userId)
        val prefs = preferencesQueries.find(uid) ?: UserPreferencesAggregate.empty(uid)
        val cmd =
            UpdatePreferenceContract(
                uid,
                body["hardRules"]?.toString() ?: "",
                body["softPrefs"]?.toString() ?: "",
                body["djStyle"]?.toString() ?: "",
                body["redLines"]?.toString() ?: "",
                clock.now(),
            )
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), prefs.version)
        preferencesUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/recommend/radio/seeds")
    fun getRadioSeeds(): ResponseEntity<Any> {
        // TODO: wire to ML query port when available
        return ResponseEntity.ok(mapOf("seeds" to emptyList<Any>(), "available" to false))
    }

    @GetMapping("/recommend/search")
    fun searchRecommendations(
        @RequestParam("q") query: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<Any> {
        // TODO: wire to ML-based recommendation search
        return ResponseEntity.ok(emptyList<Any>())
    }
}
