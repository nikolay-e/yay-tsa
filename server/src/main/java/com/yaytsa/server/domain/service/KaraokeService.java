package com.yaytsa.server.domain.service;

import com.yaytsa.server.infra.client.AudioSeparatorClient;
import com.yaytsa.server.infra.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infra.persistence.entity.ItemEntity;
import com.yaytsa.server.infra.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infra.persistence.repository.ItemRepository;
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

    private final ItemRepository itemRepository;
    private final AudioTrackRepository audioTrackRepository;
    private final AudioSeparatorClient separatorClient;
    private final Path stemsStoragePath;
    private final Path mediaRootPath;

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
            @Value("${yaytsa.media.karaoke.stems-path:/media/stems}") String stemsPath,
            @Value("${yaytsa.media.library-roots:/media}") String mediaRoot) {
        this.itemRepository = itemRepository;
        this.audioTrackRepository = audioTrackRepository;
        this.separatorClient = separatorClient;
        this.stemsStoragePath = Paths.get(stemsPath).toAbsolutePath().normalize();
        this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();

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
            if ((state == ProcessingState.READY || state == ProcessingState.FAILED)
                    && age > JOB_TTL_MS) {
                it.remove();
                log.debug("Cleaned up expired job: {}", entry.getKey());
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
            return ProcessingStatus.ready();
        }

        JobEntry job = processingJobs.get(trackId);
        if (job != null) {
            return job.status();
        }

        return ProcessingStatus.notStarted();
    }

    @Async
    public void processTrack(UUID trackId) {
        if (processingJobs.containsKey(trackId)) {
            log.info("Track {} is already being processed", trackId);
            return;
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

        updateJobStatus(trackId, ProcessingStatus.processing("Starting separation...", 0));

        Path outputDir = stemsStoragePath.resolve(trackId.toString());
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
                    .supplyAsync(() -> separatorClient.separate(audioFilePath, outputDir, trackIdStr),
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

    public Path getInstrumentalPath(UUID trackId) {
        AudioTrackEntity track = audioTrackRepository.findById(trackId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Track not found: " + trackId));

        if (!Boolean.TRUE.equals(track.getKaraokeReady()) || track.getInstrumentalPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Instrumental not available for track: " + trackId);
        }

        Path instrumentalPath = Paths.get(track.getInstrumentalPath()).toAbsolutePath().normalize();
        if (!instrumentalPath.startsWith(stemsStoragePath)) {
            log.error("Path traversal attempt in instrumental path for track {}: {}", trackId, instrumentalPath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!Files.exists(instrumentalPath)) {
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
        if (!vocalPath.startsWith(stemsStoragePath)) {
            log.error("Path traversal attempt in vocal path for track {}: {}", trackId, vocalPath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!Files.exists(vocalPath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Vocal file not found: " + trackId);
        }

        return vocalPath;
    }

    public void clearProcessingStatus(UUID trackId) {
        processingJobs.remove(trackId);
    }
}
