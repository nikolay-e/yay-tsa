package dev.yaytsa.infra.llm

import com.fasterxml.jackson.databind.ObjectMapper
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.preferences.port.PreferencesQueryPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntry
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.ListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.NewQueueEntry
import dev.yaytsa.domain.adaptive.PlaybackSignal
import dev.yaytsa.domain.adaptive.RewriteQueueTail
import dev.yaytsa.domain.adaptive.SessionState
import dev.yaytsa.domain.ml.TasteProfile
import dev.yaytsa.domain.ml.UserTrackAffinity
import dev.yaytsa.domain.preferences.PreferenceContract
import dev.yaytsa.persistence.adaptive.entity.LlmDecisionEntity
import dev.yaytsa.persistence.adaptive.jpa.LlmDecisionJpaRepository
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Hashing
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class LlmOrchestrator(
    private val adaptiveQuery: AdaptiveQueryPort,
    private val adaptiveSessionRepo: AdaptiveSessionRepository,
    private val adaptiveUseCases: AdaptiveUseCases,
    private val libraryQueries: LibraryQueries,
    private val mlQuery: MlQueryPort,
    private val preferencesQuery: PreferencesQueryPort,
    private val llmClient: LlmClient,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val decisionRepo: LlmDecisionJpaRepository,
    @Value("\${yaytsa.llm.enabled:false}") private val enabled: Boolean,
    @Value("\${yaytsa.llm.model}") private val modelId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private const val SIMILARITY_CANDIDATES = 20
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    fun processActiveSessions() {
        if (!enabled) return
        val sessions = adaptiveQuery.findAllActiveSessions()
        for (session in sessions) {
            try {
                processSession(session.userId, session.id)
            } catch (e: Exception) {
                log.error("LLM processing failed for session {}", session.id.value, e)
            }
        }
    }

    fun processSession(
        userId: UserId,
        sessionId: ListeningSessionId,
    ) {
        val session = adaptiveQuery.findSession(sessionId) ?: return
        if (session.state == SessionState.ENDED) return

        val signals = adaptiveQuery.getSignals(sessionId, 20)
        if (signals.isEmpty()) return

        val queueEntries = adaptiveQuery.getQueueEntries(sessionId)

        // Build context for LLM
        val tasteProfile = mlQuery.getTasteProfile(userId)
        val topAffinities = mlQuery.getTopAffinities(userId, 50)
        val preferenceContract = preferencesQuery.getPreferenceContract(userId)

        val seedCandidates =
            session.seedTrackId?.let {
                mlQuery.findSimilarTracks(TrackId(it.value), SIMILARITY_CANDIDATES)
            } ?: emptyList()
        val prompt =
            buildPrompt(
                session,
                signals,
                queueEntries,
                tasteProfile,
                topAffinities,
                preferenceContract,
                seedCandidates,
            )
        val callStartMs = System.currentTimeMillis()
        val response = llmClient.complete(prompt) ?: return
        val latencyMs = (System.currentTimeMillis() - callStartMs).toInt()

        val trackSuggestions = parseTrackSuggestions(response)
        if (trackSuggestions.isEmpty()) return

        // Verify tracks exist
        val existingIds = libraryQueries.trackIdsExist(trackSuggestions.map { it.first }.toSet())
        val validSuggestions = trackSuggestions.filter { it.first in existingIds }
        if (validSuggestions.isEmpty()) return

        // Reload aggregate AFTER the LLM call: queue may have changed during the round-trip
        // (~seconds), and the source of truth for `baseQueueVersion` is the aggregate, not
        // a max-of-entries snapshot taken before the LLM call. Without this, every overlap
        // with another writer (user signal, parallel scheduler tick) returned
        // `InvariantViolation: Stale queue version: expected N, got N-1`.
        val aggregate = adaptiveSessionRepo.find(sessionId) ?: return
        val freshEntries = adaptiveQuery.getQueueEntries(sessionId)
        val keepPosition = freshEntries.size

        val cmd =
            RewriteQueueTail(
                sessionId = sessionId,
                baseQueueVersion = aggregate.queueVersion,
                keepFromPosition = keepPosition,
                newTail =
                    validSuggestions.map { (trackId, reason) ->
                        NewQueueEntry(
                            id = AdaptiveQueueEntryId(UUID.randomUUID().toString()),
                            trackId = trackId,
                            addedReason = reason,
                            intentLabel = "llm-dj",
                        )
                    },
            )

        val ctx =
            CommandContext(
                userId = userId,
                protocolId = ProtocolId("LLM"),
                requestTime = clock.now(),
                idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
                expectedVersion = aggregate.version,
            )

        val result = adaptiveUseCases.execute(cmd, ctx)
        when (result) {
            is CommandResult.Success -> log.info("LLM-DJ added {} tracks to session {}", validSuggestions.size, sessionId.value)
            is CommandResult.Failed -> log.warn("LLM-DJ queue rewrite failed: {}", result.failure)
        }

        recordDecision(sessionId, signals, prompt, validSuggestions, aggregate.queueVersion, result, latencyMs)
    }

    private fun recordDecision(
        sessionId: ListeningSessionId,
        signals: List<PlaybackSignal>,
        prompt: String,
        validSuggestions: List<Pair<TrackId, String>>,
        baseQueueVersion: Long,
        result: CommandResult<*>,
        latencyMs: Int,
    ) {
        try {
            val (validation, appliedVersion, details) =
                when (result) {
                    is CommandResult.Success -> Triple("OK", baseQueueVersion + 1, null)
                    is CommandResult.Failed -> Triple("FAILED", null, result.failure.toString())
                }
            val editsJson = objectMapper.writeValueAsString(validSuggestions.map { mapOf("track_id" to it.first.value, "reason" to it.second) })
            decisionRepo.save(
                LlmDecisionEntity(
                    sessionId = UUID.fromString(sessionId.value),
                    triggerType = signals.firstOrNull()?.signalType ?: "SCHEDULED",
                    triggerSignalId = signals.firstOrNull()?.id?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                    promptHash = Hashing.sha256Hex(prompt),
                    modelId = modelId,
                    latencyMs = latencyMs,
                    intent = "llm-dj",
                    edits = editsJson,
                    baseQueueVersion = baseQueueVersion,
                    appliedQueueVersion = appliedVersion,
                    validationResult = validation,
                    validationDetails = details,
                    createdAt = clock.now(),
                ),
            )
        } catch (e: Exception) {
            log.warn("Failed to persist LLM decision audit row: {}", e.message)
        }
    }

    private fun buildPrompt(
        session: ListeningSession,
        signals: List<PlaybackSignal>,
        queue: List<AdaptiveQueueEntry>,
        tasteProfile: TasteProfile?,
        topAffinities: List<UserTrackAffinity>,
        preferenceContract: PreferenceContract?,
        seedCandidates: List<TrackId>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You are a music DJ assistant. Based on the listening session context, suggest 5 tracks to add to the queue.")
        sb.appendLine()

        appendPreferenceContract(sb, preferenceContract)

        sb.appendLine("Session: mode=${session.attentionMode}, energy=${session.energy}, mood=${session.moodTags}")
        sb.appendLine()
        session.seedTrackId?.let { seed ->
            sb.appendLine("Radio seed: this session started from track ${seed.value}.")
            sb.appendLine("Suggestions MUST stay in the same musical neighborhood (genre/timbre/structure) as the seed.")
            if (seedCandidates.isNotEmpty()) {
                sb.appendLine("Candidate tracks by audio-embedding similarity to the seed (ordered, closest first):")
                seedCandidates.forEach { sb.appendLine("  ${it.value}") }
                sb.appendLine("Prefer suggesting from this candidate set; only step outside it if respecting user constraints requires it.")
            }
            sb.appendLine()
        }
        sb.appendLine("Recent signals:")
        signals.take(10).forEach { sb.appendLine("  ${it.signalType}: track=${it.trackId.value}") }
        sb.appendLine()
        sb.appendLine("Current queue (${queue.size} entries)")
        sb.appendLine()
        if (tasteProfile?.summaryText != null) {
            sb.appendLine("User taste: ${tasteProfile.summaryText}")
        }
        sb.appendLine()
        sb.appendLine("Top affinity tracks:")
        topAffinities.take(20).forEach { sb.appendLine("  ${it.trackId.value} (score: ${it.affinityScore})") }
        sb.appendLine()
        sb.appendLine("Respond with a JSON array of objects: [{\"track_id\": \"...\", \"reason\": \"...\"}]")
        return sb.toString()
    }

    private fun appendPreferenceContract(
        sb: StringBuilder,
        contract: PreferenceContract?,
    ) {
        if (contract == null) return

        val hasContent =
            contract.hardRules.isNotBlank() ||
                contract.softPrefs.isNotBlank() ||
                contract.djStyle.isNotBlank() ||
                contract.redLines.isNotBlank()
        if (!hasContent) return

        sb.appendLine("=== USER CONSTRAINTS (must be respected) ===")
        if (contract.hardRules.isNotBlank()) {
            sb.appendLine("Hard rules: ${contract.hardRules}")
        }
        if (contract.redLines.isNotBlank()) {
            sb.appendLine("Red lines (never do this): ${contract.redLines}")
        }
        if (contract.softPrefs.isNotBlank()) {
            sb.appendLine("Soft preferences: ${contract.softPrefs}")
        }
        if (contract.djStyle.isNotBlank()) {
            sb.appendLine("DJ style: ${contract.djStyle}")
        }
        sb.appendLine("=== END USER CONSTRAINTS ===")
        sb.appendLine()
    }

    private fun parseTrackSuggestions(response: String): List<Pair<TrackId, String>> {
        val array =
            try {
                extractJsonArray(response)
            } catch (e: LlmResponseParseException) {
                log.warn("Failed to parse LLM track suggestions: {}", e.message)
                return emptyList()
            }
        if (!array.isArray) return emptyList()
        return array.mapNotNull { node ->
            val trackId = node.path("track_id").asText(null) ?: return@mapNotNull null
            if (!UUID_PATTERN.matches(trackId)) return@mapNotNull null
            val reason = node.path("reason").asText("llm-suggestion")
            TrackId(trackId) to reason
        }
    }

    private fun extractJsonArray(response: String): com.fasterxml.jackson.databind.JsonNode {
        val trimmed = response.trim()
        runCatching { objectMapper.readTree(trimmed) }
            .getOrNull()
            ?.takeIf { it.isArray }
            ?.let { return it }
        val match =
            JSON_ARRAY_PATTERN.find(trimmed)?.value
                ?: throw LlmResponseParseException("No JSON array found in response")
        return runCatching { objectMapper.readTree(match) }
            .getOrElse { throw LlmResponseParseException("Failed to parse extracted JSON array: ${it.message}") }
    }
}

class LlmResponseParseException(
    message: String,
) : RuntimeException(message)

private val JSON_ARRAY_PATTERN = Regex("\\[[\\s\\S]*\\]")
