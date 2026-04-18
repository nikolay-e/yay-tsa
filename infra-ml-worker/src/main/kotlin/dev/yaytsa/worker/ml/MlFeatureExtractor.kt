package dev.yaytsa.worker.ml

import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Background worker that delegates audio feature extraction to an external Python script.
 *
 * Architectural note: this is a **worker**, not a core command path. It bypasses core-domain
 * entirely and writes directly to the `core_v2_ml` schema via infra-persistence. It has no
 * dependency on core-domain or core-application — only on infra-persistence modules.
 *
 * The external script is responsible for computing features and writing them to the database.
 * This class orchestrates scheduling, discovery of unprocessed tracks, and error handling.
 */
@Component
@ConditionalOnProperty(name = ["yaytsa.ml.enabled"], havingValue = "true")
class MlFeatureExtractor(
    private val libraryEntityRepo: LibraryEntityRepository,
    private val trackFeaturesRepo: TrackFeaturesJpaRepository,
    @Value("\${yaytsa.ml.extractor-script:#{null}}") private val extractorScript: String?,
    @Value("\${yaytsa.ml.process-timeout-seconds:600}") private val processTimeoutSeconds: Long,
    @Value("\${yaytsa.ml.batch-size:50}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${yaytsa.ml.poll-interval-ms:300000}", initialDelay = 30_000)
    fun extractFeatures() {
        if (extractorScript == null) {
            log.debug("No extractor script configured, skipping ML extraction cycle")
            return
        }

        log.info("ML feature extraction cycle starting")

        val processedIds = trackFeaturesRepo.findAll().map { it.trackId }.toSet()

        // Page through tracks to avoid loading entire library into memory
        var page = 0
        var totalProcessed = 0
        var totalFailed = 0

        while (true) {
            val trackPage =
                libraryEntityRepo.findByEntityTypeOrderBySortNamePaged(
                    "TRACK",
                    PageRequest.of(page, batchSize),
                )
            if (trackPage.isEmpty()) break

            val unprocessed = trackPage.filter { it.id !in processedIds }
            for (entity in unprocessed) {
                val success = extractTrack(entity.id)
                if (success) totalProcessed++ else totalFailed++
            }

            page++
        }

        log.info(
            "ML feature extraction cycle complete: {} processed, {} failed",
            totalProcessed,
            totalFailed,
        )
    }

    /**
     * Invokes the external extractor script for a single track.
     *
     * The script is expected to write features directly to the `core_v2_ml.track_features` table.
     * Returns true if the script exited successfully, false otherwise.
     */
    private fun extractTrack(trackId: UUID): Boolean {
        // Double-check in case another instance processed it concurrently
        if (trackFeaturesRepo.existsById(trackId)) return true

        return try {
            val process =
                ProcessBuilder(extractorScript, trackId.toString())
                    .redirectErrorStream(false)
                    .start()

            // Drain stdout and stderr to prevent buffer deadlock
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)

            val completed = process.waitFor(processTimeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                log.error(
                    "Extractor script timed out after {}s for track {}",
                    processTimeoutSeconds,
                    trackId,
                )
                return false
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                log.warn(
                    "Extractor script failed for track {} with exit code {}. stderr: {}",
                    trackId,
                    exitCode,
                    stderr.take(2000),
                )
                return false
            }

            // Verify the script actually wrote the features
            if (!trackFeaturesRepo.existsById(trackId)) {
                log.warn(
                    "Extractor script exited 0 for track {} but no features found in DB. stdout: {}",
                    trackId,
                    stdout.take(1000),
                )
                return false
            }

            log.debug("Successfully extracted features for track {}", trackId)
            true
        } catch (e: Exception) {
            log.error("ML extraction failed for track {}", trackId, e)
            false
        }
    }
}
