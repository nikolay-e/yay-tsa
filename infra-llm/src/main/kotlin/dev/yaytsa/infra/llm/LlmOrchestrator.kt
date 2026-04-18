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
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
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
    @Value("\${yaytsa.llm.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

        val prompt = buildPrompt(session, signals, queueEntries, tasteProfile, topAffinities, preferenceContract)
        val response = llmClient.complete(prompt) ?: return

        val trackSuggestions = parseTrackSuggestions(response)
        if (trackSuggestions.isEmpty()) return

        // Verify tracks exist
        val existingIds = libraryQueries.trackIdsExist(trackSuggestions.map { it.first }.toSet())
        val validSuggestions = trackSuggestions.filter { it.first in existingIds }
        if (validSuggestions.isEmpty()) return

        val currentQueueVersion = queueEntries.maxOfOrNull { it.queueVersion } ?: 0
        val keepPosition = queueEntries.size

        val cmd =
            RewriteQueueTail(
                sessionId = sessionId,
                baseQueueVersion = currentQueueVersion,
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

        val aggregate = adaptiveSessionRepo.find(sessionId) ?: return
        val ctx =
            CommandContext(
                userId = userId,
                protocolId = ProtocolId("LLM"),
                requestTime = clock.now(),
                idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
                expectedVersion = aggregate.version,
            )

        when (val result = adaptiveUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> log.info("LLM-DJ added {} tracks to session {}", validSuggestions.size, sessionId.value)
            is CommandResult.Failed -> log.warn("LLM-DJ queue rewrite failed: {}", result.failure)
        }
    }

    private fun buildPrompt(
        session: ListeningSession,
        signals: List<PlaybackSignal>,
        queue: List<AdaptiveQueueEntry>,
        tasteProfile: TasteProfile?,
        topAffinities: List<UserTrackAffinity>,
        preferenceContract: PreferenceContract?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You are a music DJ assistant. Based on the listening session context, suggest 5 tracks to add to the queue.")
        sb.appendLine()

        appendPreferenceContract(sb, preferenceContract)

        sb.appendLine("Session: mode=${session.attentionMode}, energy=${session.energy}, mood=${session.moodTags}")
        sb.appendLine()
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
        return try {
            // Extract JSON array from response (may be wrapped in markdown code blocks)
            val jsonStr =
                response
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()
                    .let { text ->
                        val start = text.indexOf('[')
                        val end = text.lastIndexOf(']')
                        if (start >= 0 && end > start) text.substring(start, end + 1) else return emptyList()
                    }
            val array = objectMapper.readTree(jsonStr)
            if (!array.isArray) return emptyList()
            array.mapNotNull { node ->
                val trackId = node.path("track_id").asText(null) ?: return@mapNotNull null
                val reason = node.path("reason").asText("llm-suggestion")
                TrackId(trackId) to reason
            }
        } catch (e: Exception) {
            log.warn("Failed to parse LLM track suggestions: {}", e.message)
            emptyList()
        }
    }
}
