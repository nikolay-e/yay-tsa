package com.yaytsa.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.KaraokeService;
import com.yaytsa.server.domain.service.KaraokeService.ProcessingState;
import com.yaytsa.server.domain.service.KaraokeService.ProcessingStatus;
import com.yaytsa.server.util.PathUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/Karaoke")
@Tag(name = "Karaoke", description = "Vocal removal and karaoke mode endpoints")
public class KaraokeController {

  private static final Logger log = LoggerFactory.getLogger(KaraokeController.class);
  private static final long SSE_TIMEOUT_MS = 300_000; // 5 minutes
  private static final long POLL_INTERVAL_MS = 500; // Poll every 500ms
  private static final int SSE_THREAD_POOL_SIZE = 10; // Support up to 10 concurrent SSE connections

  private final KaraokeService karaokeService;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(SSE_THREAD_POOL_SIZE);
  private final boolean xAccelRedirectEnabled;
  private final String xAccelInternalPath;

  public KaraokeController(
      KaraokeService karaokeService,
      ObjectMapper objectMapper,
      @Value("${yaytsa.media.streaming.x-accel-redirect.enabled:false}")
          boolean xAccelRedirectEnabled,
      @Value("${yaytsa.media.streaming.x-accel-redirect.internal-path:/_internal/media}")
          String xAccelInternalPath) {
    this.karaokeService = karaokeService;
    this.objectMapper = objectMapper;
    this.xAccelRedirectEnabled = xAccelRedirectEnabled;
    this.xAccelInternalPath = xAccelInternalPath;
  }

  @PreDestroy
  public void shutdown() {
    log.info("Shutting down KaraokeController scheduler");
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Operation(
      summary = "Check karaoke feature availability",
      description = "Returns whether the karaoke feature is enabled on this server")
  @GetMapping("/enabled")
  public ResponseEntity<Map<String, Boolean>> isEnabled() {
    return ResponseEntity.ok(Map.of("enabled", true));
  }

  @Operation(
      summary = "Get karaoke status for a track",
      description = "Check if a track has karaoke stems available or is being processed")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Status returned"),
        @ApiResponse(responseCode = "404", description = "Track not found")
      })
  @GetMapping("/{trackId}/status")
  public ResponseEntity<ProcessingStatus> getStatus(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId) {
    return ResponseEntity.ok(karaokeService.getStatus(trackId));
  }

  @Operation(
      summary = "Stream karaoke processing status via SSE",
      description = "Real-time status updates via Server-Sent Events until processing completes")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "SSE stream started")})
  @GetMapping(value = "/{trackId}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamStatus(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId) {

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    ProcessingStatus[] lastStatus = {null};
    AtomicLong eventId = new AtomicLong(0);
    AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

    ScheduledFuture<?> future =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                ProcessingStatus status = karaokeService.getStatus(trackId);

                if (lastStatus[0] == null || !status.equals(lastStatus[0])) {
                  lastStatus[0] = status;
                  String json = objectMapper.writeValueAsString(status);
                  emitter.send(
                      SseEmitter.event()
                          .id(String.valueOf(eventId.incrementAndGet()))
                          .name("status")
                          .data(json, MediaType.APPLICATION_JSON));

                  if (status.state() == ProcessingState.READY
                      || status.state() == ProcessingState.FAILED) {
                    ScheduledFuture<?> currentFuture = futureRef.get();
                    if (currentFuture != null) {
                      currentFuture.cancel(false);
                    }
                    emitter.complete();
                  }
                }
              } catch (IllegalArgumentException e) {
                log.warn("Track {} not found during SSE polling", trackId);
                cancelFutureAndCompleteWithError(futureRef, emitter, e);
              } catch (IOException e) {
                log.debug("SSE connection closed for track {}", trackId);
                cancelFutureAndCompleteWithError(futureRef, emitter, e);
              } catch (RuntimeException e) {
                log.error("Unexpected error polling status for track {}", trackId, e);
                cancelFutureAndCompleteWithError(futureRef, emitter, e);
              }
            },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

    futureRef.set(future);

    try {
      ProcessingStatus initialStatus = karaokeService.getStatus(trackId);
      String json = objectMapper.writeValueAsString(initialStatus);
      emitter.send(
          SseEmitter.event().id("0").name("status").data(json, MediaType.APPLICATION_JSON));
    } catch (Exception e) {
      log.debug("Failed to send initial status for track {}", trackId);
    }

    emitter.onCompletion(() -> future.cancel(false));
    emitter.onTimeout(() -> future.cancel(false));
    emitter.onError(e -> future.cancel(true));

    return emitter;
  }

  @Operation(
      summary = "Request karaoke processing for a track",
      description = "Start background processing to generate vocal-removed stems")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "202", description = "Processing started"),
        @ApiResponse(responseCode = "404", description = "Track not found")
      })
  @PostMapping("/{trackId}/process")
  public ResponseEntity<ProcessingStatus> requestProcessing(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId) {

    karaokeService.processTrack(trackId);
    return ResponseEntity.accepted().body(karaokeService.getStatus(trackId));
  }

  @Operation(
      summary = "Stream instrumental (vocals removed)",
      description = "Stream the instrumental version of a track with vocals removed")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Streaming instrumental"),
        @ApiResponse(responseCode = "404", description = "Track or instrumental not found")
      })
  @GetMapping("/{trackId}/instrumental")
  public void streamInstrumental(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId,
      @Parameter(description = "API key for authentication")
          @RequestParam(value = "api_key", required = false)
          String apiKey,
      HttpServletResponse response)
      throws IOException {

    Path instrumentalPath = karaokeService.getInstrumentalPath(trackId);
    streamFile(instrumentalPath, response);
  }

  @Operation(
      summary = "Stream isolated vocals",
      description = "Stream only the vocal track (for mixing purposes)")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Streaming vocals"),
        @ApiResponse(responseCode = "404", description = "Track or vocals not found")
      })
  @GetMapping("/{trackId}/vocals")
  public void streamVocals(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId,
      @Parameter(description = "API key for authentication")
          @RequestParam(value = "api_key", required = false)
          String apiKey,
      HttpServletResponse response)
      throws IOException {

    Path vocalPath = karaokeService.getVocalPath(trackId);
    streamFile(vocalPath, response);
  }

  private void streamFile(Path filePath, HttpServletResponse response) throws IOException {
    long fileSize = Files.size(filePath);
    String mimeType = detectMimeType(filePath);

    response.setContentType(mimeType);
    response.setContentLengthLong(fileSize);
    response.setHeader("Accept-Ranges", "bytes");

    if (xAccelRedirectEnabled) {
      String encodedPath = PathUtils.encodePathForHeader(filePath.toAbsolutePath().toString());
      String redirectPath = xAccelInternalPath + encodedPath;
      response.setStatus(HttpServletResponse.SC_OK);
      response.setHeader("X-Accel-Redirect", redirectPath);
      response.setHeader("X-Accel-Buffering", "no");
      log.debug("X-Accel-Redirect karaoke file to: {}", redirectPath);
    } else {
      response.setStatus(HttpServletResponse.SC_OK);
      try (FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
          OutputStream outputStream = response.getOutputStream()) {
        fileChannel.transferTo(0, fileSize, Channels.newChannel(outputStream));
      }
    }
  }

  private void cancelFutureAndCompleteWithError(
      AtomicReference<ScheduledFuture<?>> futureRef, SseEmitter emitter, Exception e) {
    ScheduledFuture<?> currentFuture = futureRef.get();
    if (currentFuture != null) {
      currentFuture.cancel(false);
    }
    emitter.completeWithError(e);
  }

  private String detectMimeType(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (fileName.endsWith(".wav")) {
      return "audio/wav";
    } else if (fileName.endsWith(".mp3")) {
      return "audio/mpeg";
    } else if (fileName.endsWith(".flac")) {
      return "audio/flac";
    } else if (fileName.endsWith(".ogg")) {
      return "audio/ogg";
    }
    return "audio/wav";
  }
}
