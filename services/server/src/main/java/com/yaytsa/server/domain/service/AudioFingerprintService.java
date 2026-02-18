package com.yaytsa.server.domain.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Audio fingerprinting service using Chromaprint (AcoustID).
 *
 * <p>Generates acoustic fingerprints for audio files to detect duplicates, remixes, sped-up/slowed
 * versions. Uses fpcalc CLI tool from Chromaprint library.
 *
 * <p>Install: - Debian/Ubuntu: apt-get install libchromaprint-tools - Windows: Download from
 * https://acoustid.org/chromaprint - macOS: brew install chromaprint
 */
@Service
public class AudioFingerprintService {

  private static final Logger log = LoggerFactory.getLogger(AudioFingerprintService.class);

  private static final int COMMAND_TIMEOUT_SECONDS = 30;
  private static final double SIMILARITY_THRESHOLD = 0.90; // 90% match = duplicate
  private static final double SPED_UP_THRESHOLD = 1.15; // >15% faster = sped up
  private static final double SLOWED_DOWN_THRESHOLD = 0.85; // >15% slower = slowed

  private final String fpcalcPath;

  public AudioFingerprintService(@Value("${yaytsa.media.fingerprint.fpcalc-path:fpcalc}") String fpcalcPath) {
    this.fpcalcPath = fpcalcPath;
    checkFpcalcAvailable();
  }

  /**
   * Audio fingerprint result.
   *
   * @param fingerprint Chromaprint fingerprint (base64 encoded)
   * @param duration Track duration in seconds
   * @param sampleRate Audio sample rate
   */
  public record AudioFingerprint(String fingerprint, double duration, int sampleRate) {

    /**
     * Calculates similarity with another fingerprint (0.0 - 1.0).
     *
     * @param other Other fingerprint to compare
     * @return Similarity score (1.0 = identical)
     */
    public double similarity(AudioFingerprint other) {
      if (fingerprint == null || other.fingerprint == null) {
        return 0.0;
      }

      // Simple string similarity (Levenshtein-based)
      // For production, use proper Chromaprint comparison algorithm
      int distance = levenshteinDistance(fingerprint, other.fingerprint);
      int maxLen = Math.max(fingerprint.length(), other.fingerprint.length());

      if (maxLen == 0) return 1.0;

      return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Detects if this is a sped-up or slowed version of another track.
     *
     * @param other Original track fingerprint
     * @return Variant type: "original", "sped-up", "slowed"
     */
    public String detectVariant(AudioFingerprint other) {
      if (other == null || other.duration == 0) {
        return "original";
      }

      double durationRatio = this.duration / other.duration;

      if (durationRatio < SLOWED_DOWN_THRESHOLD) {
        return "sped-up";
      } else if (durationRatio > SPED_UP_THRESHOLD) {
        return "slowed";
      }

      return "original";
    }

    private int levenshteinDistance(String s1, String s2) {
      int[][] dp = new int[s1.length() + 1][s2.length() + 1];

      for (int i = 0; i <= s1.length(); i++) {
        for (int j = 0; j <= s2.length(); j++) {
          if (i == 0) {
            dp[i][j] = j;
          } else if (j == 0) {
            dp[i][j] = i;
          } else {
            dp[i][j] =
                Math.min(
                    dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1),
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
          }
        }
      }

      return dp[s1.length()][s2.length()];
    }
  }

  /**
   * Duplicate detection result.
   *
   * @param isDuplicate Whether file is a duplicate
   * @param similarity Similarity score (0.0-1.0)
   * @param variant Track variant type
   * @param suggestedSuffix Suggested suffix for title (e.g., " (sped up)")
   */
  public record DuplicateCheckResult(
      boolean isDuplicate, double similarity, String variant, String suggestedSuffix) {

    public static DuplicateCheckResult notDuplicate() {
      return new DuplicateCheckResult(false, 0.0, "original", "");
    }

    public static DuplicateCheckResult exactDuplicate(double similarity) {
      return new DuplicateCheckResult(true, similarity, "original", "");
    }

    public static DuplicateCheckResult variant(String variantType, double similarity) {
      String suffix =
          switch (variantType) {
            case "sped-up" -> " (sped up)";
            case "slowed" -> " (slowed)";
            default -> "";
          };

      return new DuplicateCheckResult(false, similarity, variantType, suffix);
    }
  }

  /**
   * Generates acoustic fingerprint for audio file.
   *
   * @param audioFile Path to audio file
   * @return Fingerprint or empty if generation failed
   */
  public Optional<AudioFingerprint> generateFingerprint(Path audioFile) {
    if (!isFingerprintingEnabled()) {
      log.debug("Audio fingerprinting is disabled (fpcalc not available)");
      return Optional.empty();
    }

    try {
      log.debug("Generating fingerprint for: {}", audioFile);

      ProcessBuilder pb = new ProcessBuilder(fpcalcPath, "-json", audioFile.toString());
      pb.redirectErrorStream(true);

      Process process = pb.start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line);
        }
      }

      boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!finished) {
        process.destroyForcibly();
        log.warn("Fingerprint generation timeout for: {}", audioFile);
        return Optional.empty();
      }

      if (process.exitValue() != 0) {
        log.warn("fpcalc failed with exit code {}: {}", process.exitValue(), output);
        return Optional.empty();
      }

      return parseFpcalcOutput(output.toString());

    } catch (IOException | InterruptedException e) {
      log.error("Failed to generate fingerprint for {}: {}", audioFile, e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return Optional.empty();
    }
  }

  /**
   * Checks if new file is a duplicate of existing track.
   *
   * @param newFingerprint New file fingerprint
   * @param existingFingerprint Existing track fingerprint
   * @return Duplicate check result
   */
  public DuplicateCheckResult checkDuplicate(
      AudioFingerprint newFingerprint, AudioFingerprint existingFingerprint) {

    if (newFingerprint == null || existingFingerprint == null) {
      return DuplicateCheckResult.notDuplicate();
    }

    double similarity = newFingerprint.similarity(existingFingerprint);

    log.debug(
        "Fingerprint similarity: {}% (threshold: {}%)",
        similarity * 100, SIMILARITY_THRESHOLD * 100);

    if (similarity >= SIMILARITY_THRESHOLD) {
      // High similarity - check if it's a variant (sped up/slowed)
      String variant = newFingerprint.detectVariant(existingFingerprint);

      if ("original".equals(variant)) {
        // Exact duplicate
        log.info("Exact duplicate detected (similarity: {}%)", similarity * 100);
        return DuplicateCheckResult.exactDuplicate(similarity);
      } else {
        // Variant (sped up or slowed)
        log.info(
            "Variant detected: {} (similarity: {}%)", variant, similarity * 100);
        return DuplicateCheckResult.variant(variant, similarity);
      }
    }

    return DuplicateCheckResult.notDuplicate();
  }

  /**
   * Checks if fingerprinting is available on this system.
   *
   * @return true if fpcalc is available
   */
  public boolean isFingerprintingEnabled() {
    return checkFpcalcAvailable();
  }

  private boolean checkFpcalcAvailable() {
    try {
      Process process = new ProcessBuilder(fpcalcPath, "-version").start();
      boolean finished = process.waitFor(5, TimeUnit.SECONDS);

      if (finished && process.exitValue() == 0) {
        log.info("Audio fingerprinting enabled (fpcalc found at: {})", fpcalcPath);
        return true;
      }
    } catch (Exception e) {
      // Ignore
    }

    log.warn(
        "Audio fingerprinting disabled (fpcalc not found). Install chromaprint: apt-get install libchromaprint-tools");
    return false;
  }

  private Optional<AudioFingerprint> parseFpcalcOutput(String json) {
    try {
      // Simple JSON parsing (avoid adding Jackson dependency just for this)
      String fingerprint = extractJsonValue(json, "fingerprint");
      String durationStr = extractJsonValue(json, "duration");
      String sampleRateStr = extractJsonValue(json, "sample_rate");

      if (fingerprint == null || durationStr == null) {
        log.warn("Invalid fpcalc output: {}", json);
        return Optional.empty();
      }

      double duration = Double.parseDouble(durationStr);
      int sampleRate = sampleRateStr != null ? Integer.parseInt(sampleRateStr) : 0;

      log.debug(
          "Fingerprint generated: {} chars, duration: {}s, sample rate: {}Hz",
          fingerprint.length(),
          duration,
          sampleRate);

      return Optional.of(new AudioFingerprint(fingerprint, duration, sampleRate));

    } catch (Exception e) {
      log.error("Failed to parse fpcalc output: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private String extractJsonValue(String json, String key) {
    String searchKey = "\"" + key + "\"";
    int keyIndex = json.indexOf(searchKey);

    if (keyIndex == -1) return null;

    int colonIndex = json.indexOf(':', keyIndex);
    if (colonIndex == -1) return null;

    int valueStart = colonIndex + 1;
    while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
      valueStart++;
    }

    if (valueStart >= json.length()) return null;

    char firstChar = json.charAt(valueStart);

    if (firstChar == '"') {
      // String value
      int valueEnd = json.indexOf('"', valueStart + 1);
      if (valueEnd == -1) return null;
      return json.substring(valueStart + 1, valueEnd);
    } else {
      // Number value
      int valueEnd = valueStart;
      while (valueEnd < json.length()
          && (Character.isDigit(json.charAt(valueEnd))
              || json.charAt(valueEnd) == '.'
              || json.charAt(valueEnd) == '-')) {
        valueEnd++;
      }
      return json.substring(valueStart, valueEnd);
    }
  }
}
