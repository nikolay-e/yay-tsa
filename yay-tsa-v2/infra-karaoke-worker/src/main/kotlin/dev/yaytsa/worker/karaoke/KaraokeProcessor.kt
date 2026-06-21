package dev.yaytsa.worker.karaoke

import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import dev.yaytsa.persistence.karaoke.jpa.KaraokeAssetJpaRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import dev.yaytsa.shared.EntityId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(name = ["yaytsa.karaoke.enabled"], havingValue = "true")
class KaraokeProcessor(
    private val libraryEntityRepo: LibraryEntityRepository,
    private val libraryQuery: LibraryQueryPort,
    private val karaokeRepo: KaraokeAssetJpaRepository,
    private val clock: Clock,
    private val separatorClient: SeparatorClient,
    @Value("\${yaytsa.karaoke.output-path:#{null}}") private val outputPath: String?,
    @Value("\${yaytsa.karaoke.demucs-command:demucs}") private val demucsCommand: String,
    @Value("\${yaytsa.karaoke.separator-url:#{null}}") private val separatorUrl: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEMUCS_TIMEOUT_MINUTES = 30L
        private const val MAX_FAILURES = 3
        private const val BATCH_LIMIT = 50
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    fun processUnready() {
        // Production delegates to the audio-separator HTTP sidecar (BS-Roformer on GPU)
        // via separatorUrl; the in-process Demucs path is the local/dev fallback. Bail
        // only when neither separation backend is available.
        val localDemucsUnavailable = demucsCommand == "unsupported" || demucsCommand.isBlank()
        if (separatorUrl.isNullOrBlank() && localDemucsUnavailable) {
            log.debug("Karaoke processor has no separation backend (separatorUrl unset, demucsCommand={})", demucsCommand)
            return
        }
        log.info("Karaoke processor checking for unprocessed tracks")

        // A track is terminal only when it succeeded (readyAt set) or exhausted its
        // retry budget; the anti-join excludes exactly those, so a bare failure row
        // does not permanently skip the track. LIMIT keeps each cycle bounded instead
        // of materializing every track and karaoke row on every run.
        val unprocessed = libraryEntityRepo.findKaraokeUnprocessedTrackIds(MAX_FAILURES, BATCH_LIMIT)

        if (unprocessed.isEmpty()) {
            log.info("No unprocessed tracks for karaoke separation")
            return
        }

        log.info("Found {} unprocessed tracks for karaoke (batch limit {})", unprocessed.size, BATCH_LIMIT)
        unprocessed.forEach { trackId ->
            try {
                processTrack(trackId)
            } catch (e: Exception) {
                log.error("Karaoke processing failed for track {}", trackId, e)
            }
        }
    }

    // No @Transactional: separation runs a long external call (HTTP sidecar up to 30 min,
    // or the local demucs subprocess) that must NOT hold a DB transaction open. Each
    // findById/save is individually transactional via the Spring Data repository.
    fun processTrack(trackId: UUID) {
        val existing = karaokeRepo.findById(trackId).orElse(null)
        if (existing?.readyAt != null) return
        if ((existing?.failCount ?: 0) >= MAX_FAILURES) return

        // resolveTrackFilePath reconstructs the absolute path (libraryRoot + sourcePath),
        // matching how streaming and lyrics resolve files. The separator validates the
        // path against its /media root, so a bare relative sourcePath is rejected with 403.
        val filePath = libraryQuery.resolveTrackFilePath(EntityId(trackId.toString())) ?: return

        if (!separatorUrl.isNullOrBlank()) {
            try {
                val result = separatorClient.separate(separatorUrl, filePath, trackId.toString())
                if (!Files.exists(Path.of(result.instrumentalPath)) || !Files.exists(Path.of(result.vocalPath))) {
                    log.warn("Separator reported success but stems missing on disk for track {}", trackId)
                    recordFailure(trackId, existing, "separator stems missing on disk")
                    return
                }
                karaokeRepo.save(
                    KaraokeAssetEntity(
                        trackId = trackId,
                        instrumentalPath = result.instrumentalPath,
                        vocalPath = result.vocalPath,
                        readyAt = clock.now(),
                    ),
                )
                log.info("Karaoke assets ready via separator for track {} in {} ms", trackId, result.processingTimeMs)
            } catch (e: Exception) {
                log.warn("Separator failed for track {}: {}", trackId, e.message)
                recordFailure(trackId, existing, e.message)
            }
            return
        }

        val outDir = Path.of(requireNotNull(outputPath) { "yaytsa.karaoke.output-path required for local demucs" }, trackId.toString())
        Files.createDirectories(outDir)

        try {
            val process =
                ProcessBuilder(
                    demucsCommand,
                    "--two-stems",
                    "vocals",
                    "-o",
                    outDir.toString(),
                    filePath,
                ).redirectErrorStream(true).start()

            // Drain process output to prevent buffer deadlock
            val processOutput = process.inputStream.bufferedReader().readText()

            val finished = process.waitFor(DEMUCS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                log.warn("Demucs timed out for track {} after {} min", trackId, DEMUCS_TIMEOUT_MINUTES)
                recordFailure(trackId, existing, "demucs timed out after $DEMUCS_TIMEOUT_MINUTES min")
                return
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                log.warn("Demucs failed for track {} (exit {}): {}", trackId, exitCode, processOutput.takeLast(500))
                recordFailure(trackId, existing, "demucs exit $exitCode")
                return
            }
        } catch (e: Exception) {
            log.warn("Demucs not available for track {}: {}", trackId, e.message)
            recordFailure(trackId, existing, e.message)
            return
        }

        // Demucs outputs to {out_dir}/{model}/{stem_name}/ structure
        // Find the actual output files after processing
        val demucsOut =
            outDir
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "wav" }
                .toList()

        val vocalFile = demucsOut.find { it.name == "vocals.wav" }?.absolutePath
        val instrumentalFile = demucsOut.find { it.name == "no_vocals.wav" || it.name == "other.wav" }?.absolutePath

        if (vocalFile == null || instrumentalFile == null) {
            log.warn("Demucs output files not found for track {} in {}", trackId, outDir)
            recordFailure(trackId, existing, "demucs output files not found")
            return
        }

        karaokeRepo.save(
            KaraokeAssetEntity(
                trackId = trackId,
                instrumentalPath = instrumentalFile,
                vocalPath = vocalFile,
                readyAt = clock.now(),
            ),
        )
        log.info("Karaoke assets ready for track {}", trackId)
    }

    // Persist the failure with an incremented counter instead of a terminal null-stem row,
    // so the scheduler retries up to MAX_FAILURES before giving up.
    private fun recordFailure(
        trackId: UUID,
        existing: KaraokeAssetEntity?,
        message: String?,
    ) {
        karaokeRepo.save(
            KaraokeAssetEntity(
                trackId = trackId,
                failCount = (existing?.failCount ?: 0) + 1,
                lastFailedAt = clock.now(),
                lastError = message?.take(1000),
            ),
        )
    }
}
