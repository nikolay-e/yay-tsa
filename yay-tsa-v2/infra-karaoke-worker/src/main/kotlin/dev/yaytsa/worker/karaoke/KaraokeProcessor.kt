package dev.yaytsa.worker.karaoke

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import dev.yaytsa.persistence.karaoke.jpa.KaraokeAssetJpaRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(name = ["yaytsa.karaoke.enabled"], havingValue = "true")
class KaraokeProcessor(
    private val libraryEntityRepo: LibraryEntityRepository,
    private val karaokeRepo: KaraokeAssetJpaRepository,
    private val clock: Clock,
    @Value("\${yaytsa.karaoke.output-path}") private val outputPath: String,
    @Value("\${yaytsa.karaoke.demucs-command:demucs}") private val demucsCommand: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEMUCS_TIMEOUT_MINUTES = 30L
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    fun processUnready() {
        log.info("Karaoke processor checking for unprocessed tracks")

        val allTrackIds =
            libraryEntityRepo
                .findByEntityTypeOrderBySortName("TRACK")
                .map { it.id }
                .toSet()

        val processedIds = karaokeRepo.findAll().map { it.trackId }.toSet()
        val unprocessed = allTrackIds - processedIds

        if (unprocessed.isEmpty()) {
            log.info("No unprocessed tracks for karaoke separation")
            return
        }

        log.info("Found {} unprocessed tracks for karaoke", unprocessed.size)
        unprocessed.forEach { trackId ->
            try {
                processTrack(trackId)
            } catch (e: Exception) {
                log.error("Karaoke processing failed for track {}", trackId, e)
            }
        }
    }

    @Transactional
    fun processTrack(trackId: UUID) {
        if (karaokeRepo.existsById(trackId)) return

        val entity = libraryEntityRepo.findById(trackId).orElse(null) ?: return
        val sourcePath = entity.sourcePath ?: return

        val outDir = Path.of(outputPath, trackId.toString())
        Files.createDirectories(outDir)

        try {
            val process =
                ProcessBuilder(
                    demucsCommand,
                    "--two-stems",
                    "vocals",
                    "-o",
                    outDir.toString(),
                    sourcePath,
                ).redirectErrorStream(true).start()

            // Drain process output to prevent buffer deadlock
            val processOutput = process.inputStream.bufferedReader().readText()

            val finished = process.waitFor(DEMUCS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                log.warn("Demucs timed out for track {} after {} min", trackId, DEMUCS_TIMEOUT_MINUTES)
                karaokeRepo.save(KaraokeAssetEntity(trackId = trackId))
                return
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                log.warn("Demucs failed for track {} (exit {}): {}", trackId, exitCode, processOutput.takeLast(500))
                karaokeRepo.save(KaraokeAssetEntity(trackId = trackId))
                return
            }
        } catch (e: Exception) {
            log.warn("Demucs not available for track {}: {}", trackId, e.message)
            karaokeRepo.save(KaraokeAssetEntity(trackId = trackId))
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
            karaokeRepo.save(KaraokeAssetEntity(trackId = trackId))
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
}
