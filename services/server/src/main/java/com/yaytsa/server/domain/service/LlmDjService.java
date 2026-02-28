package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.LlmDecisionLogEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.LlmDecisionLogRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TasteProfileRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserPreferenceContractRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmDjService {

  private static final Logger log = LoggerFactory.getLogger(LlmDjService.class);
  private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int MAX_TOOL_ROUNDS = 5;
  private static final int RECENT_SIGNALS_LIMIT = 20;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final CandidateRetrievalService candidateRetrievalService;
  private final AdaptiveQueueRepository queueRepository;
  private final PlaybackSignalRepository signalRepository;
  private final TrackFeaturesRepository featuresRepository;
  private final TasteProfileRepository tasteProfileRepository;
  private final UserPreferenceContractRepository contractRepository;
  private final LlmDecisionLogRepository decisionLogRepository;

  private final String apiKey;
  private final String model;
  private final int maxTokens;

  public LlmDjService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      CandidateRetrievalService candidateRetrievalService,
      AdaptiveQueueRepository queueRepository,
      PlaybackSignalRepository signalRepository,
      TrackFeaturesRepository featuresRepository,
      TasteProfileRepository tasteProfileRepository,
      UserPreferenceContractRepository contractRepository,
      LlmDecisionLogRepository decisionLogRepository,
      @Value("${yaytsa.adaptive-dj.anthropic-api-key:}") String apiKey,
      @Value("${yaytsa.adaptive-dj.model:claude-sonnet-4-20250514}") String model,
      @Value("${yaytsa.adaptive-dj.max-tokens:2000}") int maxTokens,
      @Value("${yaytsa.adaptive-dj.timeout-ms:8000}") int timeoutMs) {
    this.objectMapper = objectMapper;
    this.candidateRetrievalService = candidateRetrievalService;
    this.queueRepository = queueRepository;
    this.signalRepository = signalRepository;
    this.featuresRepository = featuresRepository;
    this.tasteProfileRepository = tasteProfileRepository;
    this.contractRepository = contractRepository;
    this.decisionLogRepository = decisionLogRepository;
    this.apiKey = apiKey;
    this.model = model;
    this.maxTokens = maxTokens;

    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
    requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

    this.restClient = restClientBuilder.requestFactory(requestFactory).build();
  }

  public boolean isAvailable() {
    return apiKey != null && !apiKey.isBlank();
  }

  public Optional<DjDecision> generateDecision(
      ListeningSessionEntity session, String triggerType, PlaybackSignalEntity triggerSignal) {
    if (!isAvailable()) {
      log.debug("LLM DJ not available — API key not configured");
      return Optional.empty();
    }

    long startTime = System.currentTimeMillis();

    try {
      UUID userId = session.getUser().getId();
      UUID sessionId = session.getId();

      List<AdaptiveQueueEntity> currentQueue =
          queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
              sessionId, List.of("QUEUED", "PLAYING"));

      long baseQueueVersion =
          currentQueue.stream().mapToLong(AdaptiveQueueEntity::getQueueVersion).max().orElse(0);

      String systemPrompt = buildSystemPrompt(userId, session, currentQueue);
      String userMessage = buildUserMessage(sessionId, triggerType, triggerSignal);
      String promptHash = sha256(systemPrompt + userMessage);

      List<Map<String, Object>> tools = buildToolDefinitions();
      List<Map<String, Object>> messages = new ArrayList<>();
      messages.add(Map.of("role", "user", "content", userMessage));

      Map<String, Object> apiResponse = callAnthropic(systemPrompt, messages, tools);
      int totalPromptTokens = extractInt(apiResponse, "usage", "input_tokens");
      int totalCompletionTokens = extractInt(apiResponse, "usage", "output_tokens");

      int toolRounds = 0;
      while (isToolUseResponse(apiResponse) && toolRounds < MAX_TOOL_ROUNDS) {
        toolRounds++;
        List<Map<String, Object>> contentBlocks = extractContentBlocks(apiResponse);

        messages.add(Map.of("role", "assistant", "content", contentBlocks));

        List<Map<String, Object>> toolResults = executeToolCalls(contentBlocks, userId);
        messages.add(Map.of("role", "user", "content", toolResults));

        apiResponse = callAnthropic(systemPrompt, messages, tools);
        totalPromptTokens += extractInt(apiResponse, "usage", "input_tokens");
        totalCompletionTokens += extractInt(apiResponse, "usage", "output_tokens");
      }

      String responseText = extractTextContent(apiResponse);
      DjDecision decision = parseDecision(responseText, baseQueueVersion);

      long latencyMs = System.currentTimeMillis() - startTime;
      logDecision(
          session,
          triggerType,
          triggerSignal,
          promptHash,
          totalPromptTokens,
          totalCompletionTokens,
          latencyMs,
          decision,
          "VALID");

      log.info(
          "LLM DJ decision generated in {}ms ({} tool rounds, {} edits)",
          latencyMs,
          toolRounds,
          decision.edits().size());

      return Optional.of(decision);

    } catch (Exception e) {
      long latencyMs = System.currentTimeMillis() - startTime;
      log.error("LLM DJ decision failed after {}ms: {}", latencyMs, e.getMessage(), e);
      logFailure(session, triggerType, triggerSignal, latencyMs, e.getMessage());
      return Optional.empty();
    }
  }

  private String buildSystemPrompt(
      UUID userId, ListeningSessionEntity session, List<AdaptiveQueueEntity> currentQueue) {
    var sb = new StringBuilder();
    sb.append(
        "You are an adaptive music DJ for a personal music streaming system. Your job is to"
            + " curate the playback queue based on the listener's taste, current mood signals, and"
            + " session flow.\n\n");

    Optional<TasteProfileEntity> tasteProfile = tasteProfileRepository.findById(userId);
    if (tasteProfile.isPresent()) {
      sb.append("## Taste Profile\n");
      if (tasteProfile.get().getSummaryText() != null) {
        sb.append(tasteProfile.get().getSummaryText()).append("\n");
      }
      sb.append("Profile data: ").append(tasteProfile.get().getProfile()).append("\n\n");
    }

    Optional<UserPreferenceContractEntity> contract = contractRepository.findById(userId);
    if (contract.isPresent()) {
      sb.append("## Preference Contract\n");
      sb.append("Hard rules: ").append(contract.get().getHardRules()).append("\n");
      sb.append("Red lines (NEVER play): ").append(contract.get().getRedLines()).append("\n");
      sb.append("Soft preferences: ").append(contract.get().getSoftPrefs()).append("\n");
      sb.append("DJ style: ").append(contract.get().getDjStyle()).append("\n\n");
    }

    sb.append("## Current Session\n");
    sb.append("Session state: ").append(session.getState()).append("\n");
    if (session.getSessionSummary() != null) {
      sb.append("Session summary: ").append(session.getSessionSummary()).append("\n");
    }
    sb.append("\n");

    sb.append("## Current Queue\n");
    for (var entry : currentQueue) {
      var item = entry.getItem();
      sb.append("- pos=").append(entry.getPosition());
      sb.append(" id=").append(item.getId());
      sb.append(" \"").append(item.getName()).append("\"");
      sb.append(" status=").append(entry.getStatus());
      if (entry.getIntentLabel() != null) {
        sb.append(" intent=").append(entry.getIntentLabel());
      }
      appendTrackFeatures(sb, item.getId());
      sb.append("\n");
    }
    sb.append("\n");

    sb.append("## Response Format\n");
    sb.append(
        "Use the provided tools to search the library and find suitable tracks. Then respond"
            + " with a JSON object (no markdown fence):\n");
    sb.append(
        """
        {
          "baseQueueVersion": <current queue version>,
          "intent": {
            "sessionArc": "<brief arc description>",
            "reasoning": "<why these changes>"
          },
          "edits": [
            {
              "action": "INSERT|REMOVE|REORDER",
              "position": <queue position>,
              "trackId": "<uuid>",
              "reason": "<why this track>",
              "intentLabel": "<short label>"
            }
          ],
          "sessionSummaryUpdate": "<updated session summary or null>"
        }
        """);

    return sb.toString();
  }

  private void appendTrackFeatures(StringBuilder sb, UUID trackId) {
    featuresRepository
        .findByTrackId(trackId)
        .ifPresent(
            f -> {
              sb.append(" [");
              if (f.getBpm() != null) sb.append("bpm=").append(f.getBpm());
              if (f.getEnergy() != null) sb.append(" energy=").append(f.getEnergy());
              if (f.getValence() != null) sb.append(" valence=").append(f.getValence());
              if (f.getDanceability() != null) sb.append(" dance=").append(f.getDanceability());
              if (f.getMusicalKey() != null) sb.append(" key=").append(f.getMusicalKey());
              sb.append("]");
            });
  }

  private String buildUserMessage(
      UUID sessionId, String triggerType, PlaybackSignalEntity triggerSignal) {
    var sb = new StringBuilder();
    sb.append("Trigger: ").append(triggerType).append("\n");

    if (triggerSignal != null) {
      sb.append("Signal: type=").append(triggerSignal.getSignalType());
      sb.append(" item=").append(triggerSignal.getItem().getId());
      sb.append(" \"").append(triggerSignal.getItem().getName()).append("\"");
      if (triggerSignal.getContext() != null) {
        sb.append(" context=").append(triggerSignal.getContext());
      }
      sb.append("\n");
    }

    sb.append("\nRecent signals:\n");
    var recentSignals =
        signalRepository.findBySessionIdOrderByCreatedAtDesc(
            sessionId, PageRequest.of(0, RECENT_SIGNALS_LIMIT));
    for (var signal : recentSignals) {
      sb.append("- ").append(signal.getSignalType());
      sb.append(" \"").append(signal.getItem().getName()).append("\"");
      sb.append(" at ").append(signal.getCreatedAt());
      sb.append("\n");
    }

    sb.append(
        "\nReview the queue. Use tools to find tracks if you need candidates. Then return your"
            + " decision as JSON.");

    return sb.toString();
  }

  private List<Map<String, Object>> buildToolDefinitions() {
    List<Map<String, Object>> tools = new ArrayList<>();

    var searchProps = new HashMap<String, Object>();
    searchProps.put("energy_min", Map.of("type", "number", "description", "Minimum energy (0-1)"));
    searchProps.put("energy_max", Map.of("type", "number", "description", "Maximum energy (0-1)"));
    searchProps.put("bpm_min", Map.of("type", "number", "description", "Minimum BPM"));
    searchProps.put("bpm_max", Map.of("type", "number", "description", "Maximum BPM"));
    searchProps.put(
        "valence_min", Map.of("type", "number", "description", "Minimum valence (0-1)"));
    searchProps.put(
        "valence_max", Map.of("type", "number", "description", "Maximum valence (0-1)"));
    searchProps.put(
        "arousal_min", Map.of("type", "number", "description", "Minimum arousal (0-1)"));
    searchProps.put(
        "arousal_max", Map.of("type", "number", "description", "Maximum arousal (0-1)"));
    searchProps.put(
        "vocal_max",
        Map.of(
            "type",
            "number",
            "description",
            "Max vocal/instrumental ratio (0=instrumental, 1=vocal)"));
    searchProps.put(
        "exclude_artists",
        Map.of(
            "type",
            "array",
            "items",
            Map.of("type", "string"),
            "description",
            "Artist names to exclude"));
    searchProps.put("limit", Map.of("type", "integer", "description", "Max results (default 10)"));

    var searchSchema = new HashMap<String, Object>();
    searchSchema.put("type", "object");
    searchSchema.put("properties", searchProps);
    searchSchema.put("required", List.of());

    tools.add(
        buildTool(
            "search_library",
            "Search the music library by audio feature ranges. Returns tracks matching the"
                + " specified criteria with metadata and features.",
            searchSchema));

    tools.add(
        buildTool(
            "get_similar_tracks",
            "Find tracks similar to a given track based on audio embeddings (Discogs).",
            Map.of(
                "type", "object",
                "properties",
                    Map.of(
                        "track_id",
                            Map.of("type", "string", "description", "UUID of the reference track"),
                        "limit",
                            Map.of("type", "integer", "description", "Max results (default 10)")),
                "required", List.of("track_id"))));

    tools.add(
        buildTool(
            "get_recently_overplayed",
            "Get track IDs that have been played too frequently in the last 48h. Avoid these.",
            Map.of(
                "type", "object",
                "properties",
                    Map.of(
                        "hours",
                        Map.of(
                            "type", "integer",
                            "description", "Lookback window in hours (default 48)")),
                "required", List.of())));

    var detailsProps = new HashMap<String, Object>();
    detailsProps.put(
        "track_ids",
        Map.of(
            "type",
            "array",
            "items",
            Map.of("type", "string"),
            "description",
            "List of track UUIDs"));

    var detailsSchema = new HashMap<String, Object>();
    detailsSchema.put("type", "object");
    detailsSchema.put("properties", detailsProps);
    detailsSchema.put("required", List.of("track_ids"));

    tools.add(
        buildTool(
            "get_track_details",
            "Get detailed information about specific tracks including features and metadata.",
            detailsSchema));

    return tools;
  }

  private Map<String, Object> buildTool(
      String name, String description, Map<String, Object> inputSchema) {
    return Map.of(
        "name", name,
        "description", description,
        "input_schema", inputSchema);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> callAnthropic(
      String systemPrompt, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", model);
    requestBody.put("max_tokens", maxTokens);
    requestBody.put("system", systemPrompt);
    requestBody.put("messages", messages);
    if (tools != null && !tools.isEmpty()) {
      requestBody.put("tools", tools);
    }

    String responseStr =
        restClient
            .post()
            .uri(ANTHROPIC_API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(String.class);

    try {
      return objectMapper.readValue(responseStr, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to parse Anthropic API response", e);
    }
  }

  private boolean isToolUseResponse(Map<String, Object> response) {
    String stopReason = (String) response.get("stop_reason");
    return "tool_use".equals(stopReason);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractContentBlocks(Map<String, Object> response) {
    Object content = response.get("content");
    if (content instanceof List<?> list) {
      return (List<Map<String, Object>>) list;
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> executeToolCalls(
      List<Map<String, Object>> contentBlocks, UUID sessionUserId) {
    List<Map<String, Object>> results = new ArrayList<>();

    for (var block : contentBlocks) {
      if (!"tool_use".equals(block.get("type"))) {
        continue;
      }

      String toolName = (String) block.get("name");
      String toolUseId = (String) block.get("id");
      Map<String, Object> input = (Map<String, Object>) block.get("input");

      String toolResult;
      try {
        toolResult = dispatchToolCall(toolName, input, sessionUserId);
      } catch (Exception e) {
        log.warn("Tool call {} failed: {}", toolName, e.getMessage());
        try {
          toolResult =
              objectMapper.writeValueAsString(
                  Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        } catch (JsonProcessingException je) {
          toolResult = "{\"error\": \"tool execution failed\"}";
        }
      }

      results.add(
          Map.of(
              "type", "tool_result",
              "tool_use_id", toolUseId,
              "content", toolResult));
    }

    return results;
  }

  private String dispatchToolCall(String toolName, Map<String, Object> input, UUID sessionUserId) {
    try {
      Object result =
          switch (toolName) {
            case "search_library" -> {
              int limit =
                  input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : 10;
              var filters =
                  new CandidateRetrievalService.LibrarySearchFilters(
                      toFloat(input.get("energy_min")),
                      toFloat(input.get("energy_max")),
                      toFloat(input.get("bpm_min")),
                      toFloat(input.get("bpm_max")),
                      toFloat(input.get("valence_min")),
                      toFloat(input.get("valence_max")),
                      toFloat(input.get("arousal_min")),
                      toFloat(input.get("arousal_max")),
                      toFloat(input.get("vocal_max")),
                      toStringList(input.get("exclude_artists")),
                      limit);
              yield candidateRetrievalService.searchLibrary(filters);
            }
            case "get_similar_tracks" -> {
              UUID trackId = UUID.fromString((String) input.get("track_id"));
              int limit =
                  input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : 10;
              yield candidateRetrievalService.findSimilarTracks(trackId, limit);
            }
            case "get_recently_overplayed" -> {
              int hours =
                  input.containsKey("hours") ? ((Number) input.get("hours")).intValue() : 48;
              yield candidateRetrievalService.getRecentlyOverplayed(sessionUserId, hours);
            }
            case "get_track_details" -> {
              @SuppressWarnings("unchecked")
              List<String> idStrings = (List<String>) input.get("track_ids");
              List<UUID> trackIds = idStrings.stream().map(UUID::fromString).toList();
              yield candidateRetrievalService.getTrackDetails(trackIds);
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
          };
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize tool result", e);
    }
  }

  @SuppressWarnings("unchecked")
  private String extractTextContent(Map<String, Object> response) {
    Object content = response.get("content");
    if (content instanceof List<?> blocks) {
      for (var block : blocks) {
        if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
          return (String) map.get("text");
        }
      }
    }
    throw new RuntimeException("No text content in Anthropic response");
  }

  private DjDecision parseDecision(String responseText, long fallbackQueueVersion) {
    String json = responseText.strip();
    if (json.startsWith("```")) {
      int start = json.indexOf('\n') + 1;
      int end = json.lastIndexOf("```");
      if (start > 0 && end > start) {
        json = json.substring(start, end).strip();
      }
    }

    try {
      Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});

      long baseQueueVersion =
          raw.containsKey("baseQueueVersion")
              ? ((Number) raw.get("baseQueueVersion")).longValue()
              : fallbackQueueVersion;

      DjDecision.DjIntent intent = parseIntent(raw.get("intent"));
      List<DjDecision.QueueEdit> edits = parseEdits(raw.get("edits"));
      String sessionSummaryUpdate = (String) raw.get("sessionSummaryUpdate");

      return new DjDecision(baseQueueVersion, intent, edits, sessionSummaryUpdate);

    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to parse DJ decision JSON: " + e.getMessage(), e);
    }
  }

  private DjDecision.DjIntent parseIntent(Object intentObj) {
    if (intentObj instanceof Map<?, ?> map) {
      return new DjDecision.DjIntent((String) map.get("sessionArc"), (String) map.get("reasoning"));
    }
    return new DjDecision.DjIntent("unknown", "Could not parse intent");
  }

  @SuppressWarnings("unchecked")
  private List<DjDecision.QueueEdit> parseEdits(Object editsObj) {
    if (!(editsObj instanceof List<?> list)) {
      return List.of();
    }

    List<DjDecision.QueueEdit> edits = new ArrayList<>();
    for (var item : list) {
      if (item instanceof Map<?, ?> map) {
        String actionStr = map.get("action") != null ? map.get("action").toString() : null;
        if (actionStr == null) continue;
        DjDecision.EditAction action = DjDecision.EditAction.valueOf(actionStr.toUpperCase());
        int position = map.containsKey("position") ? ((Number) map.get("position")).intValue() : 0;
        UUID trackId =
            map.get("trackId") != null ? UUID.fromString((String) map.get("trackId")) : null;
        String reason = (String) map.get("reason");
        String intentLabel = (String) map.get("intentLabel");
        edits.add(new DjDecision.QueueEdit(action, position, trackId, reason, intentLabel));
      }
    }
    return edits;
  }

  private void logDecision(
      ListeningSessionEntity session,
      String triggerType,
      PlaybackSignalEntity triggerSignal,
      String promptHash,
      int promptTokens,
      int completionTokens,
      long latencyMs,
      DjDecision decision,
      String validationResult) {
    try {
      var logEntry = new LlmDecisionLogEntity();
      logEntry.setSession(session);
      logEntry.setTriggerType(triggerType);
      logEntry.setTriggerSignal(triggerSignal);
      logEntry.setPromptHash(promptHash);
      logEntry.setPromptTokens(promptTokens);
      logEntry.setCompletionTokens(completionTokens);
      logEntry.setModelId(model);
      logEntry.setLatencyMs((int) latencyMs);
      logEntry.setBaseQueueVersion(decision.baseQueueVersion());
      logEntry.setValidationResult(validationResult);

      logEntry.setIntent(objectMapper.writeValueAsString(decision.intent()));
      logEntry.setEdits(objectMapper.writeValueAsString(decision.edits()));

      decisionLogRepository.save(logEntry);
    } catch (Exception e) {
      log.warn("Failed to save LLM decision log: {}", e.getMessage());
    }
  }

  private void logFailure(
      ListeningSessionEntity session,
      String triggerType,
      PlaybackSignalEntity triggerSignal,
      long latencyMs,
      String errorMessage) {
    try {
      var logEntry = new LlmDecisionLogEntity();
      logEntry.setSession(session);
      logEntry.setTriggerType(triggerType);
      logEntry.setTriggerSignal(triggerSignal);
      logEntry.setModelId(model);
      logEntry.setLatencyMs((int) latencyMs);
      logEntry.setValidationResult("ERROR");
      try {
        logEntry.setValidationDetails(
            objectMapper.writeValueAsString(
                Map.of("error", errorMessage != null ? errorMessage : "Unknown error")));
      } catch (JsonProcessingException je) {
        logEntry.setValidationDetails("{\"error\": \"serialization failed\"}");
      }
      decisionLogRepository.save(logEntry);
    } catch (Exception e) {
      log.warn("Failed to save LLM failure log: {}", e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private int extractInt(Map<String, Object> map, String... path) {
    Object current = map;
    for (String key : path) {
      if (current instanceof Map<?, ?> m) {
        current = m.get(key);
      } else {
        return 0;
      }
    }
    return current instanceof Number n ? n.intValue() : 0;
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      return "unavailable";
    }
  }

  private static Float toFloat(Object val) {
    if (val instanceof Number n) {
      return n.floatValue();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<String> toStringList(Object val) {
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return null;
  }
}
