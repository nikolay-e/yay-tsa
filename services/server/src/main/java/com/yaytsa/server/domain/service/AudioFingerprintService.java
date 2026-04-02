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

@Service
public class AudioFingerprintService {

  private static final Logger log = LoggerFactory.getLogger(AudioFingerprintService.class);

  private static final int COMMAND_TIMEOUT_SECONDS = 30;

  private final String fpcalcPath;

  public AudioFingerprintService(
      @Value("${yaytsa.media.fingerprint.fpcalc-path:fpcalc}") String fpcalcPath) {
    this.fpcalcPath = fpcalcPath;
    this.fpcalcAvailable = checkFpcalcAvailable();
  }

  public record AudioFingerprint(String fingerprint, double duration, int sampleRate) {

    // TODO: Replace Levenshtein with proper Chromaprint bitwise comparison for production use
    public double similarity(AudioFingerprint other) {
      if (fingerprint == null || other.fingerprint == null) {
        return 0.0;
      }

      // Guard against excessively long fingerprints that could cause OOM in Levenshtein
      if (fingerprint.length() > 5000 || other.fingerprint.length() > 5000) {
        return 0.0;
      }

      int distance = levenshteinDistance(fingerprint, other.fingerprint);
      int maxLen = Math.max(fingerprint.length(), other.fingerprint.length());

      if (maxLen == 0) return 1.0;

      return 1.0 - ((double) distance / maxLen);
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
          new BufferedReader(
              new InputStreamReader(
                  process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
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

  private volatile Boolean fpcalcAvailable;

  public boolean isFingerprintingEnabled() {
    if (fpcalcAvailable == null) {
      fpcalcAvailable = checkFpcalcAvailable();
    }
    return fpcalcAvailable;
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
        "Audio fingerprinting disabled (fpcalc not found). Install chromaprint: apt-get install"
            + " libchromaprint-tools");
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
