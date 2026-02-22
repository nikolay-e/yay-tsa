package com.yaytsa.server.infrastructure.transcoding;

import com.yaytsa.server.infrastructure.fs.PathUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FfmpegTranscoder {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(FfmpegTranscoder.class);

  private static final List<String> BROWSER_NATIVE_CODEC_PREFIXES =
      List.of("mp3", "aac", "flac", "opus", "ogg", "vorbis", "wav", "pcm", "mpeg");

  private final String ffmpegPath;
  private final Semaphore semaphore;
  private final int timeoutSeconds;

  public FfmpegTranscoder(
      @Value("${yaytsa.media.transcoding.ffmpeg-path:ffmpeg}") String ffmpegPath,
      @Value("${yaytsa.media.transcoding.max-concurrent:4}") int maxConcurrent,
      @Value("${yaytsa.media.transcoding.timeout-seconds:600}") int timeoutSeconds) {
    this.ffmpegPath = ffmpegPath;
    this.semaphore = new Semaphore(maxConcurrent);
    this.timeoutSeconds = timeoutSeconds;
    log.info(
        "FFmpeg transcoder initialized: path={}, maxConcurrent={}, timeout={}s",
        ffmpegPath,
        maxConcurrent,
        timeoutSeconds);
  }

  public static boolean isBrowserNativeCodec(String codec) {
    if (codec == null || codec.isBlank()) {
      return true;
    }
    String lower = codec.toLowerCase(Locale.ROOT);
    return BROWSER_NATIVE_CODEC_PREFIXES.stream().anyMatch(lower::startsWith);
  }

  public Optional<Path> transcodeToFlac(Path inputFile) {
    if (!semaphore.tryAcquire()) {
      log.warn("Transcoding capacity exceeded, skipping: {}", inputFile);
      return Optional.empty();
    }

    String baseName = PathUtils.getFilenameWithoutExtension(inputFile);
    Path outputFile = inputFile.resolveSibling(baseName + ".flac");

    Process process = null;
    Thread watchdog = null;
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              ffmpegPath,
              "-i",
              inputFile.toAbsolutePath().toString(),
              "-vn",
              "-c:a",
              "flac",
              "-compression_level",
              "8",
              "-y",
              outputFile.toAbsolutePath().toString());
      pb.redirectErrorStream(false);

      try {
        process = pb.start();
      } catch (IOException e) {
        log.error("Failed to start FFmpeg for {}: {}", inputFile, e.getMessage());
        return Optional.empty();
      }

      drainStderr(process, inputFile);
      watchdog = startWatchdog(process, inputFile, timeoutSeconds);

      process.getOutputStream().close();

      int exitCode = process.waitFor();
      if (exitCode == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
        log.info(
            "Transcoded {} -> {} ({} bytes)",
            inputFile.getFileName(),
            outputFile.getFileName(),
            Files.size(outputFile));
        return Optional.of(outputFile);
      }

      log.warn("FFmpeg exited with code {} for {}", exitCode, inputFile);
      Files.deleteIfExists(outputFile);
      return Optional.empty();

    } catch (IOException e) {
      log.error("Transcoding I/O error for {}: {}", inputFile, e.getMessage());
      try {
        Files.deleteIfExists(outputFile);
      } catch (IOException ignored) {
      }
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Transcoding interrupted for {}", inputFile);
      try {
        Files.deleteIfExists(outputFile);
      } catch (IOException ignored) {
      }
      return Optional.empty();
    } finally {
      if (watchdog != null) {
        watchdog.interrupt();
      }
      if (process != null) {
        process.destroyForcibly();
      }
      semaphore.release();
    }
  }

  public boolean validateTranscodedOutput(Path inputFile, Path outputFile) {
    try {
      long outputSize = Files.size(outputFile);
      if (outputSize < 1024) {
        log.warn("Transcoded file too small ({} bytes): {}", outputSize, outputFile);
        return false;
      }

      double inputDuration = probeDuration(inputFile);
      double outputDuration = probeDuration(outputFile);

      if (inputDuration <= 0 || outputDuration <= 0) {
        log.warn(
            "Could not verify duration for {} (input={}s, output={}s)",
            outputFile.getFileName(),
            inputDuration,
            outputDuration);
        return outputSize > 1024;
      }

      double tolerance = inputDuration * 0.05;
      if (Math.abs(inputDuration - outputDuration) > Math.max(tolerance, 1.0)) {
        log.warn(
            "Duration mismatch for {}: input={}s, output={}s",
            outputFile.getFileName(),
            inputDuration,
            outputDuration);
        return false;
      }

      return true;
    } catch (Exception e) {
      log.warn("Validation failed for {}: {}", outputFile, e.getMessage());
      return false;
    }
  }

  private double probeDuration(Path file) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "ffprobe",
              "-v",
              "error",
              "-show_entries",
              "format=duration",
              "-of",
              "default=noprint_wrappers=1:nokey=1",
              file.toAbsolutePath().toString());
      pb.redirectErrorStream(true);
      Process process = pb.start();
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return -1;
      }
      return output.isEmpty() ? -1 : Double.parseDouble(output);
    } catch (Exception e) {
      return -1;
    }
  }

  private Thread drainStderr(Process process, Path inputFile) {
    return Thread.ofVirtual()
        .name("ffmpeg-stderr")
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(512);
                String line;
                while ((line = reader.readLine()) != null) {
                  if (sb.length() < 4096) {
                    sb.append(line).append('\n');
                  }
                }
                if (!sb.isEmpty()) {
                  log.debug("FFmpeg stderr for {}: {}", inputFile, sb.toString().trim());
                }
              } catch (IOException ignored) {
              }
            });
  }

  private Thread startWatchdog(Process process, Path inputFile, int timeoutSeconds) {
    return Thread.ofVirtual()
        .name("ffmpeg-watchdog")
        .start(
            () -> {
              try {
                boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!exited) {
                  log.warn("FFmpeg timeout after {}s for {}, killing", timeoutSeconds, inputFile);
                  process.destroyForcibly();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
  }
}
