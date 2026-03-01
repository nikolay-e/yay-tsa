package com.yaytsa.server.domain.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.LlmDecisionLogEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class LlmDjService {

  private static final Logger log = LoggerFactory.getLogger(LlmDjService.class);
  private static final int MAX_TOOL_ROUNDS = 5;
  private static final int RECENT_SIGNALS_LIMIT = 20;
  private static final Pattern FENCE_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]+?)```");
  private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

  private final AnthropicClient client;
  private final ObjectMapper objectMapper;
  private final CandidateRetrievalService candidateRetrievalService;
  private final AdaptiveQueueRepository queueRepository;
  private final PlaybackSignalRepository signalRepository;
  private final TrackFeaturesRepository featuresRepository;
  private final TasteProfileRepository tasteProfileRepository;
  private final UserPreferenceContractRepository contractRepository;
  private final LlmDecisionLogRepository decisionLogRepository;
  private final String model;
  private final long maxTokens;

  public LlmDjService(
      ObjectMapper objectMapper,
      CandidateRetrievalService candidateRetrievalService,
      AdaptiveQueueRepository queueRepository,
      PlaybackSignalRepository signalRepository,
      TrackFeaturesRepository featuresRepository,
      TasteProfileRepository tasteProfileRepository,
      UserPreferenceContractRepository contractRepository,
      LlmDecisionLogRepository decisionLogRepository,
      @Value("${yaytsa.adaptive-dj.anthropic-api-key:}") String apiKey,
      @Value("${yaytsa.adaptive-dj.model:claude-sonnet-4-5-20250929}") String model,
      @Value("${yaytsa.adaptive-dj.max-tokens:4096}") int maxTokens,
      @Value("${yaytsa.adaptive-dj.timeout-ms:30000}") int timeoutMs) {
    this.objectMapper = objectMapper;
    this.candidateRetrievalService = candidateRetrievalService;
    this.queueRepository = queueRepository;
    this.signalRepository = signalRepository;
    this.featuresRepository = featuresRepository;
    this.tasteProfileRepository = tasteProfileRepository;
    this.contractRepository = contractRepository;
    this.decisionLogRepository = decisionLogRepository;
    this.model = model;
    this.maxTokens = maxTokens;

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

      String systemPrompt = buildSystemPrompt(userId, session, currentQueue, baseQueueVersion);
      String userMessage = buildUserMessage(sessionId, triggerType, triggerSignal);
      String promptHash = sha256(systemPrompt + userMessage);

      List<ToolUnion> tools = buildTools();
      List<MessageParam> messages = new ArrayList<>();
      messages.add(userParam(userMessage));

      Message response = callApi(systemPrompt, messages, tools);
      int totalPromptTokens = (int) response.usage().inputTokens();
      int totalCompletionTokens = (int) response.usage().outputTokens();

      int toolRounds = 0;
      while (response.stopReason().filter(StopReason.TOOL_USE::equals).isPresent()
          && toolRounds < MAX_TOOL_ROUNDS) {
        toolRounds++;
        messages.add(toAssistantParam(response));

        List<ContentBlockParam> toolResults = executeToolCalls(response, userId);
        messages.add(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(toolResults)
                .build());

        response = callApi(systemPrompt, messages, tools);
        totalPromptTokens += (int) response.usage().inputTokens();
        totalCompletionTokens += (int) response.usage().outputTokens();
      }

      String responseText = extractText(response);
      log.debug("LLM raw response: {}", responseText);

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

  private Message callApi(String systemPrompt, List<MessageParam> messages, List<ToolUnion> tools) {
    return client
        .messages()
        .create(
            MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(messages)
                .tools(tools)
                .build());
  }

  private MessageParam toAssistantParam(Message response) {
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (ContentBlock block : response.content()) {
      block
          .text()
          .ifPresent(
              tb ->
                  blocks.add(
                      ContentBlockParam.ofText(TextBlockParam.builder().text(tb.text()).build())));
      block
          .toolUse()
          .ifPresent(
              tu ->
                  blocks.add(
                      ContentBlockParam.ofToolUse(
                          ToolUseBlockParam.builder()
                              .id(tu.id())
                              .name(tu.name())
                              .input(tu._input())
                              .build())));
    }
    return MessageParam.builder()
        .role(MessageParam.Role.ASSISTANT)
        .contentOfBlockParams(blocks)
        .build();
  }

  private List<ContentBlockParam> executeToolCalls(Message response, UUID userId) {
    List<ContentBlockParam> results = new ArrayList<>();
    for (ContentBlock block : response.content()) {
      block
          .toolUse()
          .ifPresent(
              toolUse -> {
                String resultJson;
                try {
                  resultJson = dispatchToolCall(toolUse, userId);
                } catch (Exception e) {
                  log.warn("Tool call {} failed: {}", toolUse.name(), e.getMessage());
                  resultJson =
                      toJsonSafe(
                          Map.of(
                              "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                }
                results.add(
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())
                            .content(resultJson)
                            .build()));
              });
    }
    return results;
  }

  private String dispatchToolCall(ToolUseBlock toolUse, UUID userId) {
    Map<String, Object> input = jsonValueToMap(toolUse._input());
    try {
      Object result =
          switch (toolUse.name()) {
            case "search_library" -> {
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
                      toStringList(input.get("exclude_genres")),
                      intOrDefault(input, "limit", 10));
              yield candidateRetrievalService.searchLibrary(filters);
            }
            case "get_similar_tracks" ->
                candidateRetrievalService.findSimilarTracks(
                    UUID.fromString((String) input.get("track_id")),
                    intOrDefault(input, "limit", 10));
            case "get_recently_overplayed" ->
                candidateRetrievalService.getRecentlyOverplayed(
                    userId, intOrDefault(input, "hours", 48));
            case "get_track_details" -> {
              @SuppressWarnings("unchecked")
              List<String> idStrings = (List<String>) input.get("track_ids");
              yield candidateRetrievalService.getTrackDetails(
                  idStrings.stream().map(UUID::fromString).toList());
            }
            case "get_never_played_tracks" -> {
              var filters =
                  new CandidateRetrievalService.NeverPlayedFilters(
                      toFloat(input.get("energy_min")),
                      toFloat(input.get("energy_max")),
                      toFloat(input.get("bpm_min")),
                      toFloat(input.get("bpm_max")),
                      toFloat(input.get("valence_min")),
                      toFloat(input.get("valence_max")),
                      intOrDefault(input, "limit", 20));
              yield candidateRetrievalService.findNeverPlayedTracks(userId, filters);
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
          };
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize tool result", e);
    }
  }

  private String extractText(Message response) {
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(tb -> tb.text())
        .findFirst()
        .orElseThrow(() -> new RuntimeException("No text content in LLM response"));
  }

  private String buildSystemPrompt(
      UUID userId,
      ListeningSessionEntity session,
      List<AdaptiveQueueEntity> currentQueue,
      long queueVersion) {
    var sb = new StringBuilder();
    sb.append(
        "You are an adaptive music DJ for a personal music streaming system. Your job is to"
            + " curate the playback queue based on the listener's taste, mood signals, and session"
            + " flow.\n\n");

    tasteProfileRepository
        .findById(userId)
        .ifPresent(
            tp -> {
              sb.append("## Taste Profile\n");
              if (tp.getSummaryText() != null) sb.append(tp.getSummaryText()).append("\n");
              sb.append("Profile data: ").append(tp.getProfile()).append("\n\n");
            });

    contractRepository
        .findById(userId)
        .ifPresent(
            c -> {
              sb.append("## Preference Contract\n");
              sb.append("Hard rules: ").append(c.getHardRules()).append("\n");
              sb.append("Red lines (NEVER play): ").append(c.getRedLines()).append("\n");
              sb.append("Soft preferences: ").append(c.getSoftPrefs()).append("\n");
              sb.append("DJ style: ").append(c.getDjStyle()).append("\n\n");
              appendDjStyleInstructions(sb, c.getDjStyle());
            });

    sb.append("## Current Session\n");
    sb.append("Session state: ").append(session.getState()).append("\n");
    if (session.getSessionSummary() != null) {
      sb.append("Session summary: ").append(session.getSessionSummary()).append("\n");
    }

    int activeCount =
        (int) currentQueue.stream().filter(e -> !"REMOVED".equals(e.getStatus())).count();
    sb.append("\n## Current Queue (")
        .append(activeCount)
        .append(" tracks, version ")
        .append(queueVersion)
        .append(")\n");
    if (currentQueue.isEmpty()) {
      sb.append("(empty — the queue needs to be populated)\n");
    } else {
      for (var entry : currentQueue) {
        var item = entry.getItem();
        sb.append("- pos=").append(entry.getPosition());
        sb.append(" id=").append(item.getId());
        sb.append(" \"").append(item.getName()).append("\"");
        sb.append(" status=").append(entry.getStatus());
        if (entry.getIntentLabel() != null) sb.append(" intent=").append(entry.getIntentLabel());
        appendTrackFeatures(sb, item.getId());
        sb.append("\n");
      }
    }

    int tracksNeeded = Math.max(0, 10 - activeCount);
    sb.append("\n## CRITICAL INSTRUCTIONS\n");
    sb.append("1. Use the provided tools to search for candidate tracks.\n");
    sb.append("2. You MUST return INSERT edits to add tracks to the queue.\n");
    if (tracksNeeded > 0) {
      sb.append("3. The queue needs at least ")
          .append(tracksNeeded)
          .append(" more tracks. Include at least ")
          .append(tracksNeeded)
          .append(" INSERT edits.\n");
    } else {
      sb.append(
          "3. The queue is reasonably full. You may INSERT new tracks, REMOVE stale ones, or"
              + " REORDER for better flow.\n");
    }
    sb.append("4. Use track IDs (UUIDs) from tool results. Do NOT invent track IDs.\n");
    sb.append("5. Assign sequential positions starting from ")
        .append(activeCount + 1)
        .append(" for new INSERTs.\n");

    sb.append("\n## Response Format\n");
    sb.append("Respond with a JSON object (no markdown fence, no explanation outside JSON):\n");
    sb.append(
        """
        {
          "baseQueueVersion": %d,
          "intent": {"sessionArc": "<brief arc>", "reasoning": "<why these changes>"},
          "edits": [
            {"action": "INSERT", "position": <number>, "trackId": "<uuid>", "reason": "<why>", "intentLabel": "<label>"}
          ],
          "sessionSummaryUpdate": "<updated summary or null>"
        }
        """
            .formatted(queueVersion));
    return sb.toString();
  }

  private void appendDjStyleInstructions(StringBuilder sb, String djStyleJson) {
    if (djStyleJson == null || djStyleJson.isBlank()) return;
    try {
      Map<String, Object> style = objectMapper.readValue(djStyleJson, new TypeReference<>() {});
      String preset = (String) style.get("preset");
      if ("adventurous".equals(preset)) {
        sb.append("## CRITICAL: Adventurous / Discovery Mode\n");
        sb.append(
            "The user wants to discover NEW music they have NEVER heard before. This is the"
                + " #1 priority.\n\n");
        sb.append("Rules for adventurous mode:\n");
        sb.append(
            "1. You MUST use the `get_never_played_tracks` tool to find tracks the user has"
                + " never listened to.\n");
        sb.append(
            "2. ONLY add tracks the user has never played. Do NOT use `search_library` or"
                + " `get_similar_tracks` as primary sources — those return familiar music.\n");
        sb.append(
            "3. Prioritize artists the user has NOT heard before. Check the taste profile — if"
                + " an artist appears in top artists, SKIP them.\n");
        sb.append(
            "4. Maximize genre and style diversity. Avoid clustering too many similar-sounding"
                + " tracks together.\n");
        sb.append(
            "5. Still respect energy/valence from the session mood, but within those"
                + " constraints, always prefer the unfamiliar.\n");
        sb.append(
            "6. Label tracks with intentLabel 'discovery' so the UI shows them as new finds.\n\n");
      } else if ("smooth".equals(preset)) {
        sb.append("## Smooth DJ Mode\n");
        sb.append(
            "Prioritize familiar music the user already enjoys. Create gradual transitions"
                + " between tracks with similar energy, BPM, and mood. Use `get_similar_tracks`"
                + " to find natural progressions. Avoid jarring genre or tempo changes.\n\n");
      }
    } catch (JsonProcessingException e) {
      log.debug("Could not parse djStyle for instructions: {}", e.getMessage());
    }
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
      if (triggerSignal.getContext() != null)
        sb.append(" context=").append(triggerSignal.getContext());
      sb.append("\n");
    }
    sb.append("\nRecent signals:\n");
    var recentSignals =
        signalRepository.findBySessionIdOrderByCreatedAtDesc(
            sessionId, PageRequest.of(0, RECENT_SIGNALS_LIMIT));
    int volumeCount = 0;
    for (var signal : recentSignals) {
      if ("VOLUME_CHANGE".equals(signal.getSignalType())) {
        if (++volumeCount > 2) continue;
      }
      sb.append("- ").append(signal.getSignalType());
      sb.append(" \"").append(signal.getItem().getName()).append("\"");
      sb.append(" at ").append(signal.getCreatedAt()).append("\n");
    }
    sb.append("\nSearch for tracks using the tools, then return your queue edits as JSON.");
    return sb.toString();
  }

  private List<ToolUnion> buildTools() {
    List<ToolUnion> tools = new ArrayList<>();

    var searchProps = new HashMap<String, Object>();
    searchProps.put("energy_min", prop("number", "Minimum energy (0-1)"));
    searchProps.put("energy_max", prop("number", "Maximum energy (0-1)"));
    searchProps.put("bpm_min", prop("number", "Minimum BPM"));
    searchProps.put("bpm_max", prop("number", "Maximum BPM"));
    searchProps.put("valence_min", prop("number", "Minimum valence (0-1)"));
    searchProps.put("valence_max", prop("number", "Maximum valence (0-1)"));
    searchProps.put("arousal_min", prop("number", "Minimum arousal (0-1)"));
    searchProps.put("arousal_max", prop("number", "Maximum arousal (0-1)"));
    searchProps.put(
        "vocal_max", prop("number", "Max vocal/instrumental ratio (0=instrumental, 1=vocal)"));
    searchProps.put("exclude_artists", arrayProp("string", "Artist names to exclude"));
    searchProps.put("exclude_genres", arrayProp("string", "Genre names to exclude"));
    searchProps.put("limit", prop("integer", "Max results (default 10)"));
    tools.add(
        sdkTool(
            "search_library",
            "Search the music library by audio feature ranges. Returns tracks matching criteria.",
            searchProps));

    tools.add(
        sdkTool(
            "get_similar_tracks",
            "Find tracks similar to a given track based on audio embeddings.",
            Map.of(
                "track_id",
                prop("string", "UUID of the reference track"),
                "limit",
                prop("integer", "Max results (default 10)")),
            "track_id"));

    tools.add(
        sdkTool(
            "get_recently_overplayed",
            "Get track IDs played too frequently in the last 48h. Avoid these.",
            Map.of("hours", prop("integer", "Lookback window in hours (default 48)"))));

    tools.add(
        sdkTool(
            "get_track_details",
            "Get detailed information about specific tracks including features and metadata.",
            Map.of("track_ids", arrayProp("string", "List of track UUIDs")),
            "track_ids"));

    var neverPlayedProps = new HashMap<String, Object>();
    neverPlayedProps.put("energy_min", prop("number", "Minimum energy (0-1)"));
    neverPlayedProps.put("energy_max", prop("number", "Maximum energy (0-1)"));
    neverPlayedProps.put("bpm_min", prop("number", "Minimum BPM"));
    neverPlayedProps.put("bpm_max", prop("number", "Maximum BPM"));
    neverPlayedProps.put("valence_min", prop("number", "Minimum valence (0-1)"));
    neverPlayedProps.put("valence_max", prop("number", "Maximum valence (0-1)"));
    neverPlayedProps.put("limit", prop("integer", "Max results (default 20)"));
    tools.add(
        sdkTool(
            "get_never_played_tracks",
            "Find tracks the user has NEVER listened to. Essential for discovery mode.",
            neverPlayedProps));

    return tools;
  }

  private ToolUnion sdkTool(
      String name, String description, Map<String, Object> properties, String... required) {
    var schemaBuilder = Tool.InputSchema.builder().properties(JsonValue.from(properties));
    if (required.length > 0) {
      schemaBuilder.putAdditionalProperty("required", JsonValue.from(List.of(required)));
    }
    return ToolUnion.ofTool(
        Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(schemaBuilder.build())
            .build());
  }

  private DjDecision parseDecision(String responseText, long fallbackQueueVersion) {
    String json = responseText.strip();
    var fenceMatcher = FENCE_PATTERN.matcher(json);
    if (fenceMatcher.find()) {
      json = fenceMatcher.group(1).strip();
    }
    try {
      return buildDecision(
          objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}),
          fallbackQueueVersion);
    } catch (JsonProcessingException ignored) {
    }
    var jsonMatcher = JSON_OBJECT_PATTERN.matcher(responseText);
    if (jsonMatcher.find()) {
      try {
        return buildDecision(
            objectMapper.readValue(
                jsonMatcher.group().strip(), new TypeReference<Map<String, Object>>() {}),
            fallbackQueueVersion);
      } catch (JsonProcessingException ignored) {
      }
    }
    throw new RuntimeException(
        "Failed to parse DJ decision JSON: "
            + responseText.substring(0, Math.min(200, responseText.length())));
  }

  private DjDecision buildDecision(Map<String, Object> raw, long fallbackQueueVersion) {
    long baseQueueVersion =
        raw.containsKey("baseQueueVersion")
            ? ((Number) raw.get("baseQueueVersion")).longValue()
            : fallbackQueueVersion;
    return new DjDecision(
        baseQueueVersion,
        parseIntent(raw.get("intent")),
        parseEdits(raw.get("edits")),
        (String) raw.get("sessionSummaryUpdate"));
  }

  private DjDecision.DjIntent parseIntent(Object intentObj) {
    if (intentObj instanceof Map<?, ?> map)
      return new DjDecision.DjIntent((String) map.get("sessionArc"), (String) map.get("reasoning"));
    return new DjDecision.DjIntent("unknown", "Could not parse intent");
  }

  private List<DjDecision.QueueEdit> parseEdits(Object editsObj) {
    if (!(editsObj instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<?, ?>) item)
        .filter(map -> map.get("action") != null)
        .map(
            map ->
                new DjDecision.QueueEdit(
                    DjDecision.EditAction.valueOf(map.get("action").toString().toUpperCase()),
                    map.containsKey("position") ? ((Number) map.get("position")).intValue() : 0,
                    map.get("trackId") != null
                        ? UUID.fromString((String) map.get("trackId"))
                        : null,
                    (String) map.get("reason"),
                    (String) map.get("intentLabel")))
        .toList();
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
      var entry = createBaseLogEntry(session, triggerType, triggerSignal, latencyMs);
      entry.setPromptHash(promptHash);
      entry.setPromptTokens(promptTokens);
      entry.setCompletionTokens(completionTokens);
      entry.setBaseQueueVersion(decision.baseQueueVersion());
      entry.setValidationResult(validationResult);
      entry.setIntent(objectMapper.writeValueAsString(decision.intent()));
      entry.setEdits(objectMapper.writeValueAsString(decision.edits()));
      decisionLogRepository.save(entry);
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
      var entry = createBaseLogEntry(session, triggerType, triggerSignal, latencyMs);
      entry.setValidationResult("ERROR");
      entry.setValidationDetails(
          toJsonSafe(Map.of("error", errorMessage != null ? errorMessage : "Unknown error")));
      decisionLogRepository.save(entry);
    } catch (Exception e) {
      log.warn("Failed to save LLM failure log: {}", e.getMessage());
    }
  }

  private LlmDecisionLogEntity createBaseLogEntry(
      ListeningSessionEntity session,
      String triggerType,
      PlaybackSignalEntity triggerSignal,
      long latencyMs) {
    var entry = new LlmDecisionLogEntity();
    entry.setSession(session);
    entry.setTriggerType(triggerType);
    entry.setTriggerSignal(triggerSignal);
    entry.setModelId(model);
    entry.setLatencyMs((int) latencyMs);
    return entry;
  }

  private String toJsonSafe(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{\"error\": \"serialization failed\"}";
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> jsonValueToMap(JsonValue value) {
    if (value == null) return Map.of();
    try {
      return value.convert(new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static MessageParam userParam(String text) {
    return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
  }

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  private static Map<String, Object> arrayProp(String itemType, String description) {
    return Map.of("type", "array", "items", Map.of("type", itemType), "description", description);
  }

  private static int intOrDefault(Map<String, Object> input, String key, int defaultValue) {
    Object val = input.get(key);
    return val instanceof Number n ? n.intValue() : defaultValue;
  }

  static Float toFloat(Object val) {
    return val instanceof Number n ? n.floatValue() : null;
  }

  @SuppressWarnings("unchecked")
  private static List<String> toStringList(Object val) {
    if (val instanceof List<?> list)
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    return null;
  }

  private static String sha256(String input) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
