package com.yaytsa.server.infrastructure.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Service for downloading audio from URLs using yt-dlp.
 *
 * <p>Supports YouTube, SoundCloud, and other sources supported by yt-dlp.
 */
@Component
public class YtDlpDownloader {

  private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);
  private static final int DOWNLOAD_TIMEOUT_SECONDS = 600; // 10 minutes

  @Value("${yaytsa.ytdlp.path:yt-dlp}")
  private String ytDlpPath;

  /**
   * Download audio from URL to a temporary file.
   *
   * @param url the URL to download from
   * @return path to the downloaded file
   * @throws IOException if download fails
   */
  public Path downloadAudio(String url) throws IOException {
    // Create temp directory for download
    Path tempDir = Files.createTempDirectory("ytdlp-download-");
    Path outputTemplate = tempDir.resolve("%(title)s.%(ext)s");

    List<String> command = new ArrayList<>();
    command.add(ytDlpPath);
    command.add("--extract-audio");
    command.add("--audio-format");
    command.add("mp3"); // Convert to mp3 for compatibility
    command.add("--audio-quality");
    command.add("0"); // Best quality
    command.add("--output");
    command.add(outputTemplate.toString());
    command.add("--no-playlist"); // Only download single video
    command.add("--no-part"); // Don't use .part files
    command.add(url);

    log.info("Downloading audio from URL: {}", url);
    log.debug("yt-dlp command: {}", String.join(" ", command));

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);

      Process process = pb.start();

      // Read output for logging
      StringBuilder output = new StringBuilder();
      try (var reader = process.inputReader()) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
          log.debug("yt-dlp: {}", line);
        }
      }

      boolean finished = process.waitFor(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!finished) {
        process.destroyForcibly();
        throw new IOException("Download timed out after " + DOWNLOAD_TIMEOUT_SECONDS + " seconds");
      }

      int exitCode = process.exitValue();
      if (exitCode != 0) {
        log.error("yt-dlp failed with exit code {}: {}", exitCode, output);
        throw new IOException("Failed to download audio: " + output);
      }

      // Find the downloaded file (yt-dlp creates it with the video title)
      Path downloadedFile = Files.list(tempDir)
          .filter(Files::isRegularFile)
          .findFirst()
          .orElseThrow(() -> new IOException("Downloaded file not found in " + tempDir));

      log.info("Successfully downloaded audio to: {}", downloadedFile);
      return downloadedFile;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    } catch (IOException e) {
      // Clean up temp directory on error
      try {
        Files.walk(tempDir)
            .sorted((a, b) -> -a.compareTo(b))
            .forEach(path -> {
              try {
                Files.deleteIfExists(path);
              } catch (IOException ex) {
                log.warn("Failed to delete temp file: {}", path, ex);
              }
            });
      } catch (IOException cleanupEx) {
        log.warn("Failed to clean up temp directory: {}", tempDir, cleanupEx);
      }
      throw e;
    }
  }
}
