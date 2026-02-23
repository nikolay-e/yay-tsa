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
public class OpenAiLlmProvider implements LlmAnalysisProvider {

  private static final Logger log = LoggerFactory.getLogger(OpenAiLlmProvider.class);

  private static final String API_URL = "https://api.openai.com/v1/chat/completions";
  private static final String DEFAULT_MODEL = "gpt-4o-mini";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final AppSettingsService settingsService;
  private final ObjectMapper objectMapper;

  public OpenAiLlmProvider(AppSettingsService settingsService, ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
  }

  private String getApiKey() {
    return settingsService.get("radio.llm.openai.api-key", "OPENAI_API_KEY");
  }

  private String getModel() {
    String model = settingsService.get("radio.llm.openai.model");
    return model.isBlank() ? DEFAULT_MODEL : model;
  }

  @Override
  public Optional<TrackAnalysis> analyzeTrack(TrackContext context) {
    if (!isEnabled()) return Optional.empty();

    try {
      String prompt = LlmPromptBuilder.buildPrompt(context);
      String requestBody =
          objectMapper.writeValueAsString(
              new OpenAiRequest(
                  getModel(),
                  256,
                  List.of(new OpenAiMessage("user", prompt))));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_URL))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + getApiKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("OpenAI API returned {}", response.statusCode());
        return Optional.empty();
      }

      OpenAiResponse oaiResponse =
          objectMapper.readValue(response.body(), OpenAiResponse.class);
      if (oaiResponse.choices == null || oaiResponse.choices.isEmpty()) {
        return Optional.empty();
      }

      String text = oaiResponse.choices.getFirst().message.content;
      return LlmPromptBuilder.parseResponse(text, response.body());

    } catch (Exception e) {
      log.warn("OpenAI analysis failed for {} - {}: {}", context.artist(), context.title(), e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "openai";
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

  record OpenAiRequest(
      String model,
      @JsonProperty("max_tokens") int maxTokens,
      List<OpenAiMessage> messages) {}

  record OpenAiMessage(String role, String content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OpenAiResponse(List<OpenAiChoice> choices) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OpenAiChoice(OpenAiMessageContent message) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OpenAiMessageContent(String content) {}
}
