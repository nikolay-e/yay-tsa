package com.yaytsa.server.domain.service.radio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AppSettingsService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ClaudeLlmProvider implements LlmAnalysisProvider {

  private static final Logger log = LoggerFactory.getLogger(ClaudeLlmProvider.class);

  private static final String API_URL = "https://api.anthropic.com/v1/messages";
  private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final AppSettingsService settingsService;
  private final ObjectMapper objectMapper;

  public ClaudeLlmProvider(AppSettingsService settingsService, ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
  }

  private String getApiKey() {
    return settingsService.get("radio.llm.claude.api-key", "CLAUDE_API_KEY");
  }

  private String getModel() {
    String model = settingsService.get("radio.llm.claude.model");
    return model.isBlank() ? DEFAULT_MODEL : model;
  }

  @Override
  public Optional<TrackAnalysis> analyzeTrack(TrackContext context) {
    if (!isEnabled()) return Optional.empty();

    try {
      String prompt = LlmPromptBuilder.buildPrompt(context);
      String requestBody =
          objectMapper.writeValueAsString(
              new ClaudeRequest(
                  getModel(),
                  256,
                  List.of(new ClaudeMessage("user", prompt))));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_URL))
              .timeout(TIMEOUT)
              .header("x-api-key", getApiKey())
              .header("anthropic-version", "2023-06-01")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Claude API returned {}", response.statusCode());
        return Optional.empty();
      }

      ClaudeResponse claudeResponse =
          objectMapper.readValue(response.body(), ClaudeResponse.class);
      if (claudeResponse.content == null || claudeResponse.content.isEmpty()) {
        return Optional.empty();
      }

      String text = claudeResponse.content.getFirst().text;
      return LlmPromptBuilder.parseResponse(text, response.body());

    } catch (Exception e) {
      log.warn("Claude analysis failed for {} - {}: {}", context.artist(), context.title(), e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "claude";
  }

  @Override
  public String getModelName() {
    return getModel();
  }

  @PreDestroy
  public void close() {
    httpClient.close();
  }

  @Override
  public boolean isEnabled() {
    return !getApiKey().isBlank();
  }

  record ClaudeRequest(
      String model,
      @JsonProperty("max_tokens") int maxTokens,
      List<ClaudeMessage> messages) {}

  record ClaudeMessage(String role, String content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ClaudeResponse(List<ClaudeContent> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ClaudeContent(String type, String text) {}
}
