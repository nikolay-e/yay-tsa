package dev.yaytsa.infranotifications.scrobble

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty("yaytsa.scrobbling.listenbrainz.enabled", havingValue = "true")
class ListenBrainzScrobbleSubmitter(
    private val playHistoryJpa: PlayHistoryJpaRepository,
    private val libraryQuery: LibraryQueryPort,
    private val authQueries: AuthQueries,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    @Value("\${yaytsa.scrobbling.listenbrainz.token:}") private val token: String,
    @Value("\${yaytsa.scrobbling.listenbrainz.username:}") private val username: String,
    @Value("\${yaytsa.scrobbling.listenbrainz.api-url:https://api.listenbrainz.org}") private val apiUrl: String,
    @Value("\${yaytsa.scrobbling.listenbrainz.batch-size:50}") private val batchSize: Int,
    @Value("\${yaytsa.scrobbling.listenbrainz.max-age-days:7}") private val maxAgeDays: Long,
    @Value("\${yaytsa.scrobbling.listenbrainz.retry-cooldown-seconds:60}") private val retryCooldownSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    @Volatile
    private var retryNotBefore: Instant = Instant.EPOCH

    @Volatile
    private var warnedMisconfigured = false

    @Volatile
    private var warnedUserNotFound = false

    // play_history.user_id stores the internal UserId (a UUID minted at account creation), not
    // the Jellyfin login name — so the configured username must be resolved to that UserId before
    // it can be used to query play_history. Usernames aren't renamed at runtime in this system, so
    // caching the first successful resolution for the process lifetime is safe.
    @Volatile
    private var resolvedUserId: UserId? = null

    @Scheduled(fixedDelayString = "\${yaytsa.scrobbling.listenbrainz.poll-interval-ms:30000}")
    fun poll() {
        try {
            submitPendingScrobbles()
        } catch (ex: Exception) {
            countFailed("error")
            log.warn("ListenBrainz scrobble cycle failed, will retry next tick", ex)
        }
    }

    private fun resolveConfiguredUserId(): UserId? {
        resolvedUserId?.let { return it }
        val user = authQueries.findByUsername(username)
        if (user == null) {
            if (!warnedUserNotFound) {
                warnedUserNotFound = true
                log.warn(
                    "ListenBrainz scrobbling configured username '{}' does not match any known user; skipping submission",
                    username,
                )
            }
            return null
        }
        warnedUserNotFound = false
        resolvedUserId = user.id
        return user.id
    }

    private fun submitPendingScrobbles() {
        // A single ListenBrainz token belongs to one external account. The server can host
        // multiple Jellyfin users, so submission is scoped to exactly one configured username's
        // plays — otherwise every user's listening history would be merged into that one account.
        if (token.isBlank() || username.isBlank()) {
            if (!warnedMisconfigured) {
                warnedMisconfigured = true
                log.warn(
                    "ListenBrainz scrobbling is enabled but token or username is not configured; skipping submission",
                )
            }
            return
        }
        val userId = resolveConfiguredUserId() ?: return
        val now = clock.now()
        if (now.isBefore(retryNotBefore)) return
        val cutoff = now.minus(Duration.ofDays(maxAgeDays))
        val pending =
            playHistoryJpa.findByUserIdAndCompletedTrueAndScrobbledFalseAndRecordedAtAfterOrderByRecordedAtAsc(
                userId.value,
                cutoff,
                PageRequest.of(0, batchSize),
            )
        if (pending.isEmpty()) return
        // Audiobook chapters are plays, not music listens — submitting them pollutes the
        // ListenBrainz history. Mark them scrobbled so they never resurface in the queue.
        val musical = dropAudiobooks(pending)
        val (resolved, unresolvable) = musical.map { it to resolveListen(it) }.partition { it.second != null }
        if (unresolvable.isNotEmpty()) {
            playHistoryJpa.markScrobbled(unresolvable.map { it.first.id })
            countFailed("unresolvable", unresolvable.size)
            log.warn("Dropped {} play-history entries whose track metadata could not be resolved", unresolvable.size)
        }
        if (resolved.isEmpty()) return
        submitListens(resolved.map { it.first.id to it.second!! }, now, splitOnRejection = true)
    }

    private fun dropAudiobooks(pending: List<PlayHistoryEntity>): List<PlayHistoryEntity> {
        val (audiobooks, musical) = pending.partition { isAudiobook(it) }
        if (audiobooks.isNotEmpty()) {
            playHistoryJpa.markScrobbled(audiobooks.map { it.id })
            meterRegistry
                .counter("yaytsa.scrobble.skipped", "target", "listenbrainz", "reason", "audiobook")
                .increment(audiobooks.size.toDouble())
        }
        return musical
    }

    private fun submitListens(
        listens: List<Pair<UUID, Map<String, Any>>>,
        now: Instant,
        splitOnRejection: Boolean,
    ) {
        when (val outcome = postListens(listens.map { it.second })) {
            Outcome.Accepted -> {
                playHistoryJpa.markScrobbled(listens.map { it.first })
                meterRegistry
                    .counter("yaytsa.scrobble.submitted", "target", "listenbrainz")
                    .increment(listens.size.toDouble())
            }
            is Outcome.RetryLater -> {
                retryNotBefore = now.plusSeconds(outcome.cooldownSeconds)
                countFailed("transient", listens.size)
                log.warn("ListenBrainz submission of {} listen(s) failed transiently, retrying after {}s", listens.size, outcome.cooldownSeconds)
            }
            Outcome.Rejected -> {
                if (splitOnRejection && listens.size > 1) {
                    for (listen in listens) {
                        if (clock.now().isBefore(retryNotBefore)) return
                        submitListens(listOf(listen), now, splitOnRejection = false)
                    }
                } else {
                    playHistoryJpa.markScrobbled(listens.map { it.first })
                    countFailed("rejected", listens.size)
                    log.warn("ListenBrainz rejected {} listen(s), dropping them", listens.size)
                }
            }
        }
    }

    private fun postListens(payloads: List<Map<String, Any>>): Outcome {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "listen_type" to if (payloads.size == 1) "single" else "import",
                    "payload" to payloads,
                ),
            )
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$apiUrl/1/submit-listens"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Token $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> Outcome.Accepted
                response.statusCode() == 429 -> Outcome.RetryLater(retryAfterSeconds(response))
                response.statusCode() >= 500 -> Outcome.RetryLater(retryCooldownSeconds)
                else -> {
                    log.warn("ListenBrainz returned status {}: {}", response.statusCode(), response.body())
                    Outcome.Rejected
                }
            }
        } catch (ex: IOException) {
            log.warn("ListenBrainz request failed: {}", ex.message)
            Outcome.RetryLater(retryCooldownSeconds)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.RetryLater(retryCooldownSeconds)
        }
    }

    private fun retryAfterSeconds(response: HttpResponse<String>): Long =
        response
            .headers()
            .firstValue("Retry-After")
            .map { it.toLongOrNull() }
            .orElse(null) ?: retryCooldownSeconds

    private fun isAudiobook(entry: PlayHistoryEntity): Boolean {
        val track = libraryQuery.getTrack(EntityId(entry.itemId)) ?: return false
        return dev.yaytsa.application.recommendation.MusicSurfaceFilter
            .isAudiobookTrack(track)
    }

    private fun resolveListen(entry: PlayHistoryEntity): Map<String, Any>? {
        val track = libraryQuery.getTrack(EntityId(entry.itemId)) ?: return null
        val album = track.albumId?.let { libraryQuery.getAlbum(it) }
        val artistId = track.albumArtistId ?: album?.artistId
        val artistName = artistId?.let { libraryQuery.getArtist(it)?.name } ?: return null
        val additionalInfo =
            buildMap<String, Any> {
                track.durationMs?.let { put("duration_ms", it) }
                put("submission_client", "yaytsa")
            }
        val trackMetadata =
            buildMap<String, Any> {
                put("artist_name", artistName)
                put("track_name", track.name)
                album?.name?.let { put("release_name", it) }
                put("additional_info", additionalInfo)
            }
        return mapOf(
            "listened_at" to entry.startedAt.epochSecond,
            "track_metadata" to trackMetadata,
        )
    }

    private fun countFailed(
        reason: String,
        count: Int = 1,
    ) {
        meterRegistry
            .counter("yaytsa.scrobble.failed", "target", "listenbrainz", "reason", reason)
            .increment(count.toDouble())
    }

    private sealed interface Outcome {
        data object Accepted : Outcome

        data class RetryLater(
            val cooldownSeconds: Long,
        ) : Outcome

        data object Rejected : Outcome
    }
}
