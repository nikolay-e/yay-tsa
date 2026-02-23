package com.yaytsa.server.domain.service.radio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LlmPromptBuilder {

  private static final Logger log = LoggerFactory.getLogger(LlmPromptBuilder.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  private LlmPromptBuilder() {}

  static String buildPrompt(LlmAnalysisProvider.TrackContext ctx) {
    String lyrics = "N/A";
    if (ctx.lyrics() != null && !ctx.lyrics().isBlank()) {
      String raw = ctx.lyrics().length() > 500 ? ctx.lyrics().substring(0, 500) + "..." : ctx.lyrics();
      // Sanitize lyrics to prevent prompt injection
      lyrics = raw.replace("\"", "'").replaceAll("[\\x00-\\x1F\\x7F]", " ");
    }

    return "Analyze this music track. Return ONLY valid JSON, no other text.\n"
        + "Track: \""
        + safe(ctx.artist())
        + "\" - \""
        + safe(ctx.title())
        + "\", Album: \""
        + safe(ctx.album())
        + "\" ("
        + (ctx.year() != null ? ctx.year() : "unknown")
        + "), Genre: "
        + safe(ctx.genre())
        + "\n"
        + "Lyrics: "
        + lyrics
        + "\n\n"
        + "Response format:\n"
        + "{\"mood\":\"happy|sad|calm|energetic|aggressive|romantic|melancholic|nostalgic\","
        + "\"energy\":1-10,\"language\":\"en|ru|de|fr|es|it|ja|ko|zh|instrumental\","
        + "\"themes\":\"keyword1,keyword2\",\"valence\":1-10,\"danceability\":1-10}";
  }

  static Optional<LlmAnalysisProvider.TrackAnalysis> parseResponse(
      String text, String rawResponse) {
    try {
      // Extract JSON from response (LLM may wrap it in markdown code blocks)
      String json = text.trim();
      if (json.startsWith("```")) {
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
          json = json.substring(start, end + 1);
        }
      }

      AnalysisJson parsed = mapper.readValue(json, AnalysisJson.class);

      return Optional.of(
          new LlmAnalysisProvider.TrackAnalysis(
              parsed.mood,
              clamp(parsed.energy, 1, 10),
              parsed.language,
              parsed.themes,
              clamp(parsed.valence, 1, 10),
              clamp(parsed.danceability, 1, 10),
              rawResponse));
    } catch (Exception e) {
      log.warn("Failed to parse LLM response: {}", e.getMessage());
      log.debug("Raw LLM text: {}", text);
      return Optional.empty();
    }
  }

  private static String safe(String s) {
    if (s == null) return "Unknown";
    // Sanitize to prevent prompt injection via track metadata
    String sanitized = s.replace("\"", "'").replace("\\", "");
    // Remove control characters
    sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F]", " ");
    // Limit length
    return sanitized.length() > 200 ? sanitized.substring(0, 200) : sanitized;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AnalysisJson(
      String mood, int energy, String language, String themes, int valence, int danceability) {}
}
