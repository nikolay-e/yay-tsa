package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.AudioSeparatorClient;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class KaraokeService {

    private static final Logger log = LoggerFactory.getLogger(KaraokeService.class);
    private static final long JOB_TTL_MS = 3600_000; // 1 hour TTL for completed jobs
    private static final long SEPARATION_TIMEOUT_MINUTES = 10;
    private static final long STALE_PROCESSING_TIMEOUT_MS = SEPARATION_TIMEOUT_MINUTES * 60 * 1000 + 60_000;

    private final ItemRepository itemRepository;
    private final AudioTrackRepository audioTrackRepository;
    private final AudioSeparatorClient separatorClient;
    private final Path mediaRootPath;
    private final Path stemsRootPath;

    private final Map<UUID, JobEntry> processingJobs = new ConcurrentHashMap<>();
    private final ExecutorService separationExecutor = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    private record JobEntry(ProcessingStatus status, Instant createdAt) {}

    public enum ProcessingState {
        NOT_STARTED,
        PROCESSING,
        READY,
        FAILED
    }

    public record ProcessingStatus(
            ProcessingState state,
            String message,
            Integer progressPercent
    ) {
        public static ProcessingStatus notStarted() {
            return new ProcessingStatus(ProcessingState.NOT_STARTED, null, null);
        }

        public static ProcessingStatus processing(String message, int progress) {
            return new ProcessingStatus(ProcessingState.PROCESSING, message, progress);
        }

        public static ProcessingStatus ready() {
            return new ProcessingStatus(ProcessingState.READY, null, 100);
        }

        public static ProcessingStatus failed(String message) {
            return new ProcessingStatus(ProcessingState.FAILED, message, null);
        }
    }

    public KaraokeService(
            ItemRepository itemRepository,
            AudioTrackRepository audioTrackRepository,
            AudioSeparatorClient separatorClient,
            @Value("${yaytsa.media.library.roots:/media}") String mediaRoot,
            @Value("${yaytsa.media.karaoke.stems-path:/app/stems}") String stemsPath) {
        this.itemRepository = itemRepository;
        this.audioTrackRepository = audioTrackRepository;
        this.separatorClient = separatorClient;
        this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
        this.stemsRootPath = Paths.get(stemsPath).toAbsolutePath().normalize();

        cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredJobs, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupExpiredJobs() {
        long now = Instant.now().toEpochMilli();
        Iterator<Map.Entry<UUID, JobEntry>> it = processingJobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, JobEntry> entry = it.next();
            ProcessingState state = entry.getValue().status().state();
            long age = now - entry.getValue().createdAt().toEpochMilli();

            boolean shouldRemove = false;
            String reason = null;

            if ((state == ProcessingState.READY || state == ProcessingState.FAILED)
                    && age > JOB_TTL_MS) {
                shouldRemove = true;
                reason = "expired " + state;
            } else if (state == ProcessingState.PROCESSING && age > STALE_PROCESSING_TIMEOUT_MS) {
                shouldRemove = true;
                reason = "stale PROCESSING";
            }

            if (shouldRemove) {
                it.remove();
                log.debug("Cleaned up {} job: {}", reason, entry.getKey());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down KaraokeService executors");
        cleanupScheduler.shutdown();
        separationExecutor.shutdown();
        try {
            if (!separationExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                separationExecutor.shutdownNow();
            }
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            separationExecutor.shutdownNow();
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ProcessingStatus getStatus(UUID trackId) {
        AudioTrackEntity track = audioTrackRepository.findById(trackId).orElse(null);

        if (track != null && Boolean.TRUE.equals(track.getKaraokeReady())) {
            if (validateStemFilesExist(track)) {
                return ProcessingStatus.ready();
            }
            resetKaraokeState(track, "Stem files missing, resetting karaoke state");
        }

        JobEntry job = processingJobs.get(trackId);
        if (job != null) {
            return job.status();
        }

        if (track != null && tryRecoverFromOrphanedFiles(track)) {
            return ProcessingStatus.ready();
        }

        return ProcessingStatus.notStarted();
    }

    private boolean tryRecoverFromOrphanedFiles(AudioTrackEntity track) {
        ItemEntity item = itemRepository.findById(track.getItemId()).orElse(null);
        if (item == null || item.getPath() == null) {
            return false;
        }

        Path audioFilePath = Paths.get(item.getPath()).toAbsolutePath().normalize();
        if (!audioFilePath.startsWith(mediaRootPath)) {
            return false;
        }

        Path karaokeDir = audioFilePath.getParent().resolve(".karaoke");
        String baseName = getFileNameWithoutExtension(audioFilePath.getFileName().toString());
        Path instrumentalPath = karaokeDir.resolve(baseName + "_instrumental.wav");
        Path vocalPath = karaokeDir.resolve(baseName + "_vocals.wav");

        if (Files.exists(instrumentalPath)) {
            log.info("Recovering orphaned karaoke files for track {}: {}", track.getItemId(), instrumentalPath);
            track.setKaraokeReady(true);
            track.setInstrumentalPath(instrumentalPath.toString());
            track.setVocalPath(Files.exists(vocalPath) ? vocalPath.toString() : "");
            audioTrackRepository.save(track);
            return true;
        }

        return false;
    }

    private String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private boolean validateStemFilesExist(AudioTrackEntity track) {
        if (track.getInstrumentalPath() == null || track.getVocalPath() == null) {
            return false;
        }
        Path instrumentalPath = Paths.get(track.getInstrumentalPath()).toAbsolutePath().normalize();
        Path vocalPath = Paths.get(track.getVocalPath()).toAbsolutePath().normalize();
        return isStemPathSafe(instrumentalPath) && Files.exists(instrumentalPath)
                && isStemPathSafe(vocalPath) && Files.exists(vocalPath);
    }

    private void resetKaraokeState(AudioTrackEntity track, String reason) {
        log.warn("{} for track {}", reason, track.getItemId());
        track.setKaraokeReady(false);
        track.setInstrumentalPath(null);
        track.setVocalPath(null);
        audioTrackRepository.save(track);
        processingJobs.remove(track.getItemId());
    }

    @Async
    public void processTrack(UUID trackId) {
        JobEntry existingJob = processingJobs.get(trackId);
        if (existingJob != null) {
            ProcessingState state = existingJob.status().state();
            long ageMs = Instant.now().toEpochMilli() - existingJob.createdAt().toEpochMilli();

            if (state == ProcessingState.PROCESSING && ageMs < STALE_PROCESSING_TIMEOUT_MS) {
                log.info("Track {} is already being processed", trackId);
                return;
            }

            if (state == ProcessingState.FAILED ||
                (state == ProcessingState.PROCESSING && ageMs >= STALE_PROCESSING_TIMEOUT_MS)) {
                log.info("Retrying track {} (previous state: {}, age: {}ms)", trackId, state, ageMs);
                processingJobs.remove(trackId);
            }
        }

        AudioTrackEntity track = audioTrackRepository.findById(trackId).orElse(null);
        if (track == null) {
            log.error("Track not found: {}", trackId);
            return;
        }

        if (Boolean.TRUE.equals(track.getKaraokeReady())) {
            log.info("Track {} already has karaoke stems", trackId);
            return;
        }

        ItemEntity item = itemRepository.findById(trackId).orElse(null);
        if (item == null || item.getPath() == null) {
            log.error("Track {} has no associated file path", trackId);
            updateJobStatus(trackId, ProcessingStatus.failed("Track file not found"));
            return;
        }

        Path audioFilePath = Paths.get(item.getPath()).toAbsolutePath().normalize();
        if (!audioFilePath.startsWith(mediaRootPath)) {
            log.error("Path traversal attempt detected for track {}: {}", trackId, audioFilePath);
            updateJobStatus(trackId, ProcessingStatus.failed("Invalid file path"));
            return;
        }
        if (!Files.exists(audioFilePath)) {
            log.error("Audio file not found: {}", audioFilePath);
            updateJobStatus(trackId, ProcessingStatus.failed("Audio file not found"));
            return;
        }

        if (tryRecoverFromOrphanedFiles(track)) {
            log.info("Recovered existing karaoke files for track {}, skipping processing", trackId);
            updateJobStatus(trackId, ProcessingStatus.ready());
            return;
        }

        updateJobStatus(trackId, ProcessingStatus.processing("Starting separation...", 0));

        String trackIdStr = trackId.toString();

        ScheduledFuture<?> progressPoller = cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                AudioSeparatorClient.ProgressStatus progress = separatorClient.getProgress(trackIdStr);
                if ("PROCESSING".equals(progress.state())) {
                    updateJobStatus(trackId, ProcessingStatus.processing(
                            progress.message() != null ? progress.message() : "Processing...",
                            progress.progress()
                    ));
                }
            } catch (Exception e) {
                log.debug("Progress poll failed for {}: {}", trackId, e.getMessage());
            }
        }, 500, 500, TimeUnit.MILLISECONDS);

        try {
            AudioSeparatorClient.SeparationResult result = CompletableFuture
                    .supplyAsync(() -> separatorClient.separate(audioFilePath, trackIdStr),
                            separationExecutor)
                    .get(SEPARATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            progressPoller.cancel(false);
            updateJobStatus(trackId, ProcessingStatus.processing("Saving to database...", 95));

            track.setKaraokeReady(true);
            track.setInstrumentalPath(result.instrumentalPath());
            track.setVocalPath(result.vocalPath());
            audioTrackRepository.save(track);

            updateJobStatus(trackId, ProcessingStatus.ready());
            log.info("Karaoke processing completed for track {} in {}ms",
                    trackId, result.processingTimeMs());

        } catch (TimeoutException e) {
            progressPoller.cancel(true);
            log.error("Karaoke processing timed out for track {}", trackId);
            updateJobStatus(trackId, ProcessingStatus.failed("Processing timed out"));
        } catch (Exception e) {
            progressPoller.cancel(true);
            log.error("Karaoke processing failed for track {}: {}", trackId, e.getMessage(), e);
            updateJobStatus(trackId, ProcessingStatus.failed(e.getMessage()));
        }
    }

    private void updateJobStatus(UUID trackId, ProcessingStatus status) {
        processingJobs.put(trackId, new JobEntry(status, Instant.now()));
    }

    private boolean isStemPathSafe(Path path) {
        return path.startsWith(mediaRootPath) || path.startsWith(stemsRootPath);
    }

    public Path getInstrumentalPath(UUID trackId) {
        AudioTrackEntity track = audioTrackRepository.findById(trackId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Track not found: " + trackId));

        if (!Boolean.TRUE.equals(track.getKaraokeReady()) || track.getInstrumentalPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Instrumental not available for track: " + trackId);
        }

        Path instrumentalPath = Paths.get(track.getInstrumentalPath()).toAbsolutePath().normalize();
        if (!isStemPathSafe(instrumentalPath)) {
            log.error("Path traversal attempt in instrumental path for track {}: {}", trackId, instrumentalPath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!Files.exists(instrumentalPath)) {
            resetKaraokeState(track, "Instrumental file not found");
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Instrumental file not found: " + trackId);
        }

        return instrumentalPath;
    }

    public Path getVocalPath(UUID trackId) {
        AudioTrackEntity track = audioTrackRepository.findById(trackId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Track not found: " + trackId));

        if (!Boolean.TRUE.equals(track.getKaraokeReady()) || track.getVocalPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Vocal track not available for track: " + trackId);
        }

        Path vocalPath = Paths.get(track.getVocalPath()).toAbsolutePath().normalize();
        if (!isStemPathSafe(vocalPath)) {
            log.error("Path traversal attempt in vocal path for track {}: {}", trackId, vocalPath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!Files.exists(vocalPath)) {
            resetKaraokeState(track, "Vocal file not found");
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Vocal file not found: " + trackId);
        }

        return vocalPath;
    }

    public void clearProcessingStatus(UUID trackId) {
        processingJobs.remove(trackId);
    }
}
