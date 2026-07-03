package dev.yaytsa.worker.scanner

import dev.yaytsa.application.library.port.ReplayGainBackfillTriggerPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import org.jaudiotagger.audio.AudioFileIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ReplayGainBackfillSummary(
    val examined: Int,
    val populated: Int,
    val taglessMarked: Int,
    val skippedMissingFile: Int,
)

@Component
class ReplayGainBackfill(
    private val trackRepo: AudioTrackRepository,
    private val clock: Clock,
    @Value("\${yaytsa.library.music-path:#{null}}") private val musicPath: String?,
    @Value("\${yaytsa.scanner.replaygain-backfill-on-startup:true}") private val runOnStartup: Boolean,
) : ReplayGainBackfillTriggerPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val running = AtomicBoolean(false)

    override fun triggerBackfill(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        thread(name = "replaygain-backfill", isDaemon = true) {
            try {
                runBackfill()
            } finally {
                running.set(false)
            }
        }
        return true
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (runOnStartup) triggerBackfill()
    }

    fun runBackfill(): ReplayGainBackfillSummary {
        var afterId = MIN_UUID
        var examined = 0
        var populated = 0
        var taglessMarked = 0
        var skippedMissingFile = 0
        while (true) {
            val batch = trackRepo.findReplayGainBackfillCandidates(afterId, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (row in batch) {
                examined++
                when (processCandidate(row[0] as UUID, row[1] as? String, row[2] as? String)) {
                    Outcome.POPULATED -> populated++
                    Outcome.TAGLESS_MARKED -> taglessMarked++
                    Outcome.FILE_MISSING -> skippedMissingFile++
                }
            }
            afterId = batch.last()[0] as UUID
            log.info(
                "ReplayGain backfill progress: {} examined, {} populated, {} tagless marked, {} missing files",
                examined,
                populated,
                taglessMarked,
                skippedMissingFile,
            )
        }
        if (examined > 0) {
            log.info(
                "ReplayGain backfill complete: {} examined, {} populated, {} tagless marked, {} missing files",
                examined,
                populated,
                taglessMarked,
                skippedMissingFile,
            )
        }
        return ReplayGainBackfillSummary(examined, populated, taglessMarked, skippedMissingFile)
    }

    private enum class Outcome { POPULATED, TAGLESS_MARKED, FILE_MISSING }

    private fun processCandidate(
        entityId: UUID,
        sourcePath: String?,
        libraryRoot: String?,
    ): Outcome {
        val root = (libraryRoot ?: musicPath)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: return Outcome.FILE_MISSING
        val file = sourcePath?.let { root.resolve(it) } ?: return Outcome.FILE_MISSING
        if (!Files.isRegularFile(file)) return Outcome.FILE_MISSING
        val tag = runCatching { AudioFileIO.read(file.toFile()).tag }.getOrNull()
        val gains = ReplayGainTags.read(tag)
        val trackRow = trackRepo.findById(entityId).orElse(null) ?: return Outcome.FILE_MISSING
        trackRow.replaygainTrackGain = gains.trackGain
        trackRow.replaygainAlbumGain = gains.albumGain
        trackRow.replaygainTrackPeak = gains.trackPeak
        trackRow.replaygainCheckedAt = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
        trackRepo.save(trackRow)
        val anyGainFound = gains.trackGain != null || gains.albumGain != null || gains.trackPeak != null
        return if (anyGainFound) Outcome.POPULATED else Outcome.TAGLESS_MARKED
    }

    companion object {
        private const val BATCH_SIZE = 500
        private val MIN_UUID = UUID(0L, 0L)
    }
}
