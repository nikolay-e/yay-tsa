package com.yaytsa.server.domain.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class LlmSessionParamService {

  private static final Logger log = LoggerFactory.getLogger(LlmSessionParamService.class);
  private static final int RECENT_SIGNALS_LIMIT = 20;

  private final AnthropicClient client;
  private final String model;
  private final int maxTokens;
  private final TasteProfileService tasteProfileService;
  private final PlaybackSignalRepository signalRepository;
  private final ObjectMapper objectMapper;

  public record DjSessionParams(
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      String arc,
      List<String> avoidArtists,
      List<String> preferGenres,
      List<String> avoidGenres,
      String sessionSummaryUpdate) {}

  public LlmSessionParamService(
      TasteProfileService tasteProfileService,
      PlaybackSignalRepository signalRepository,
      ObjectMapper objectMapper,
      @Value("${yaytsa.adaptive-dj.anthropic-api-key:}") String apiKey,
      @Value("${yaytsa.adaptive-dj.model:claude-haiku-4-5-20251001}") String model,
      @Value("${yaytsa.adaptive-dj.timeout-ms:30000}") long timeoutMs) {
    this.tasteProfileService = tasteProfileService;
    this.signalRepository = signalRepository;
    this.objectMapper = objectMapper;
    this.model = model;
    this.maxTokens = 512;

    if (apiKey != null && !apiKey.isBlank()) {
      this.client =
          AnthropicOkHttpClient.builder()
              .apiKey(apiKey)
              .timeout(Duration.ofMillis(timeoutMs))
              .build();
    } else {
      this.client = null;
    }
  }

  public boolean isAvailable() {
    return client != null;
  }

  public Optional<DjSessionParams> generateSessionParams(
      ListeningSessionEntity session, String triggerType, PlaybackSignalEntity triggerSignal) {

    if (!isAvailable()) return Optional.empty();

    try {
      String systemPrompt = buildSystemPrompt(session);
      String userMessage = buildUserMessage(session, triggerType, triggerSignal);

      MessageCreateParams params =
          MessageCreateParams.builder()
              .model(model)
              .maxTokens(maxTokens)
              .system(systemPrompt)
              .addUserMessage(userMessage)
              .build();

      Message response = client.messages().create(params);
      String text = extractText(response);
      return parseParams(text);
    } catch (Exception e) {
      log.warn("LLM session param generation failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private String buildSystemPrompt(ListeningSessionEntity session) {
    var sb = new StringBuilder();
    sb.append(
        "You are a DJ assistant. Based on the user's taste profile and recent listening signals,"
            + " output ONLY a JSON object with these fields:\n"
            + "- targetEnergy: float 0-1 (desired energy level)\n"
            + "- targetValence: float 0-1 (desired mood, 0=dark/melancholic, 1=bright/happy)\n"
            + "- explorationWeight: float 0-1 (0=stick to favorites, 1=maximize discovery)\n"
            + "- arc: string describing the session direction (e.g. \"building energy\", \"winding"
            + " down\")\n"
            + "- avoidArtists: list of artist names to avoid in next batch\n"
            + "- preferGenres: list of genre names to prioritize (e.g. [\"Metal\", \"Rock\"])\n"
            + "- avoidGenres: list of genre names to avoid (e.g. [\"Pop\", \"Country\"])\n"
            + "- sessionSummaryUpdate: brief update to session narrative\n\n"
            + "Output ONLY valid JSON, no explanation.\n\n");

    TasteProfileEntity profile = tasteProfileService.getProfile(session.getUser().getId());
    if (profile != null && profile.getSummaryText() != null) {
      sb.append("User taste profile: ").append(profile.getSummaryText()).append("\n\n");
    }

    var seedGenres = session.getSeedGenreList();
    if (!seedGenres.isEmpty()) {
      sb.append("Session seed genres: ").append(String.join(", ", seedGenres)).append("\n");
    }

    if (session.getEnergy() != null) {
      sb.append("Current session energy: ").append(session.getEnergy()).append("/10\n");
    }
    if (session.getIntensity() != null) {
      sb.append("Current session intensity: ").append(session.getIntensity()).append("/10\n");
    }

    return sb.toString();
  }

  private String buildUserMessage(
      ListeningSessionEntity session, String triggerType, PlaybackSignalEntity triggerSignal) {
    var sb = new StringBuilder();
    sb.append("Trigger: ").append(triggerType);
    if (triggerSignal != null) {
      sb.append(" (").append(triggerSignal.getSignalType());
      if (triggerSignal.getItem() != null) {
        sb.append(" on '").append(triggerSignal.getItem().getName()).append("'");
      }
      sb.append(")");
    }
    sb.append("\n\nRecent signals:\n");

    var recentSignals =
        signalRepository.findBySessionIdOrderByCreatedAtDesc(
            session.getId(), PageRequest.of(0, RECENT_SIGNALS_LIMIT));
    for (var signal : recentSignals) {
      sb.append("- ")
          .append(signal.getSignalType())
          .append(": ")
          .append(signal.getItem() != null ? signal.getItem().getName() : "unknown")
          .append("\n");
    }

    return sb.toString();
  }

  private String extractText(Message response) {
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(tb -> tb.text())
        .findFirst()
        .orElse("");
  }

  private Optional<DjSessionParams> parseParams(String text) {
    try {
      String json = text.strip();
      if (json.startsWith("```")) {
        int start = json.indexOf('\n') + 1;
        int end = json.lastIndexOf("```");
        if (start > 0 && end > start) json = json.substring(start, end).strip();
      }

      JsonNode node = objectMapper.readTree(json);
      float energy =
          node.has("targetEnergy") ? (float) node.get("targetEnergy").asDouble(0.5) : 0.5f;
      float valence =
          node.has("targetValence") ? (float) node.get("targetValence").asDouble(0.5) : 0.5f;
      float exploration =
          node.has("explorationWeight")
              ? (float) node.get("explorationWeight").asDouble(0.3)
              : 0.3f;
      String arc = node.has("arc") ? node.get("arc").asText("") : "";
      List<String> avoidArtists = new ArrayList<>();
      if (node.has("avoidArtists") && node.get("avoidArtists").isArray()) {
        for (var artistNode : node.get("avoidArtists")) {
          avoidArtists.add(artistNode.asText());
        }
      }
      List<String> preferGenres = new ArrayList<>();
      if (node.has("preferGenres") && node.get("preferGenres").isArray()) {
        for (var genreNode : node.get("preferGenres")) {
          preferGenres.add(genreNode.asText());
        }
      }
      List<String> avoidGenres = new ArrayList<>();
      if (node.has("avoidGenres") && node.get("avoidGenres").isArray()) {
        for (var genreNode : node.get("avoidGenres")) {
          avoidGenres.add(genreNode.asText());
        }
      }
      String summary =
          node.has("sessionSummaryUpdate") ? node.get("sessionSummaryUpdate").asText("") : "";

      return Optional.of(
          new DjSessionParams(
              energy, valence, exploration, arc, avoidArtists, preferGenres, avoidGenres, summary));
    } catch (Exception e) {
      log.warn("Failed to parse LLM session params: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
