package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QueuePolicyValidator {

  private static final Logger log = LoggerFactory.getLogger(QueuePolicyValidator.class);
  private static final int DEFAULT_MAX_ARTIST_CONSECUTIVE = 2;
  private static final int DEFAULT_NO_REPEAT_HOURS = 3;
  private static final int MAX_QUEUE_SIZE = 15;
  private static final Comparator<AdaptiveQueueEntity> BY_POSITION =
      Comparator.comparingInt(AdaptiveQueueEntity::getPosition);

  private final ItemRepository itemRepository;
  private final PlayHistoryRepository playHistoryRepository;
  private final ObjectMapper objectMapper;

  public QueuePolicyValidator(
      ItemRepository itemRepository,
      PlayHistoryRepository playHistoryRepository,
      ObjectMapper objectMapper) {
    this.itemRepository = itemRepository;
    this.playHistoryRepository = playHistoryRepository;
    this.objectMapper = objectMapper;
  }

  public ValidationResult validate(
      DjDecision decision,
      List<AdaptiveQueueEntity> currentQueue,
      UserPreferenceContractEntity preferences,
      ListeningSessionEntity session) {
    HardRules hardRules = parseHardRules(preferences);
    List<ValidatedEdit> approvedEdits = new ArrayList<>();
    List<RejectedEdit> rejectedEdits = new ArrayList<>();
    List<AdaptiveQueueEntity> simulatedQueue = new ArrayList<>(currentQueue);

    for (DjDecision.QueueEdit edit : decision.edits()) {
      switch (edit.action()) {
        case INSERT ->
            validateInsert(edit, simulatedQueue, hardRules, session, approvedEdits, rejectedEdits);
        case REMOVE ->
            validateTrackInQueue(edit, simulatedQueue, approvedEdits, rejectedEdits, true);
        case REORDER ->
            validateTrackInQueue(edit, simulatedQueue, approvedEdits, rejectedEdits, false);
      }
    }
    String outcome = determineOutcome(decision.edits().size(), approvedEdits.size());
    return new ValidationResult(approvedEdits, rejectedEdits, outcome);
  }

  private void validateInsert(
      DjDecision.QueueEdit edit,
      List<AdaptiveQueueEntity> simulatedQueue,
      HardRules hardRules,
      ListeningSessionEntity session,
      List<ValidatedEdit> approved,
      List<RejectedEdit> rejected) {
    var itemOpt = itemRepository.findById(edit.trackId());
    if (itemOpt.isEmpty()) {
      rejected.add(new RejectedEdit(edit, "Track does not exist: " + edit.trackId()));
      return;
    }
    if (countActiveEntries(simulatedQueue) >= MAX_QUEUE_SIZE) {
      rejected.add(new RejectedEdit(edit, "Queue size limit reached (" + MAX_QUEUE_SIZE + ")"));
      return;
    }
    if (isActiveInQueue(edit.trackId(), simulatedQueue)) {
      rejected.add(new RejectedEdit(edit, "Track already in queue: " + edit.trackId()));
      return;
    }
    ItemEntity item = itemOpt.get();
    String artistName = resolveArtistName(item);
    if (artistName != null
        && violatesConsecutiveArtistLimit(
            edit.position(), artistName, simulatedQueue, hardRules.maxArtistConsecutive)) {
      rejected.add(
          new RejectedEdit(
              edit,
              "Exceeds consecutive artist limit ("
                  + hardRules.maxArtistConsecutive
                  + ") for: "
                  + artistName));
      return;
    }
    if (violatesNoRepeatHours(edit.trackId(), session, hardRules.noRepeatTrackHours)) {
      rejected.add(
          new RejectedEdit(
              edit, "Track played within last " + hardRules.noRepeatTrackHours + " hours"));
      return;
    }
    approved.add(ValidatedEdit.from(edit));
    var simulated = new AdaptiveQueueEntity();
    simulated.setItem(item);
    simulated.setPosition(edit.position());
    simulated.setStatus("QUEUED");
    simulatedQueue.add(simulated);
    simulatedQueue.sort(BY_POSITION);
  }

  private void validateTrackInQueue(
      DjDecision.QueueEdit edit,
      List<AdaptiveQueueEntity> simulatedQueue,
      List<ValidatedEdit> approved,
      List<RejectedEdit> rejected,
      boolean markRemoved) {
    if (!isActiveInQueue(edit.trackId(), simulatedQueue)) {
      rejected.add(new RejectedEdit(edit, "Track not found in active queue: " + edit.trackId()));
      return;
    }
    approved.add(ValidatedEdit.from(edit));
    if (markRemoved) {
      simulatedQueue.stream()
          .filter(e -> e.getItem().getId().equals(edit.trackId()))
          .forEach(e -> e.setStatus("REMOVED"));
    }
  }

  private HardRules parseHardRules(UserPreferenceContractEntity preferences) {
    if (preferences == null || preferences.getHardRules() == null)
      return new HardRules(DEFAULT_MAX_ARTIST_CONSECUTIVE, DEFAULT_NO_REPEAT_HOURS);
    try {
      Map<String, Object> rules =
          objectMapper.readValue(preferences.getHardRules(), new TypeReference<>() {});
      return new HardRules(
          intFromEitherKey(
              rules,
              "maxArtistConsecutive",
              "max_artist_consecutive",
              DEFAULT_MAX_ARTIST_CONSECUTIVE),
          intFromEitherKey(
              rules, "noRepeatHours", "no_repeat_track_hours", DEFAULT_NO_REPEAT_HOURS));
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse hard_rules JSONB, using defaults", e);
      return new HardRules(DEFAULT_MAX_ARTIST_CONSECUTIVE, DEFAULT_NO_REPEAT_HOURS);
    }
  }

  private static int intFromEitherKey(
      Map<String, Object> map, String camelKey, String snakeKey, int defaultValue) {
    Object val = map.get(camelKey);
    if (val == null) val = map.get(snakeKey);
    return val instanceof Number n ? n.intValue() : defaultValue;
  }

  private static int intOrDefault(Map<String, Object> map, String key, int defaultValue) {
    Object val = map.get(key);
    return val instanceof Number n ? n.intValue() : defaultValue;
  }

  private long countActiveEntries(List<AdaptiveQueueEntity> queue) {
    return queue.stream().filter(e -> !"REMOVED".equals(e.getStatus())).count();
  }

  private boolean isActiveInQueue(UUID trackId, List<AdaptiveQueueEntity> queue) {
    return queue.stream()
        .anyMatch(e -> e.getItem().getId().equals(trackId) && !"REMOVED".equals(e.getStatus()));
  }

  static String resolveArtistName(ItemEntity item) {
    ItemEntity parent = item.getParent();
    if (parent == null) return null;
    ItemEntity grandparent = parent.getParent();
    return grandparent != null ? grandparent.getName() : parent.getName();
  }

  private List<AdaptiveQueueEntity> getActiveSorted(List<AdaptiveQueueEntity> queue) {
    return queue.stream()
        .filter(e -> !"REMOVED".equals(e.getStatus()))
        .sorted(BY_POSITION)
        .toList();
  }

  private boolean violatesConsecutiveArtistLimit(
      int insertPosition, String artistName, List<AdaptiveQueueEntity> queue, int maxConsecutive) {
    List<AdaptiveQueueEntity> active = getActiveSorted(queue);
    int insertIndex = 0;
    for (int i = 0; i < active.size(); i++) {
      if (active.get(i).getPosition() >= insertPosition) {
        insertIndex = i;
        break;
      }
      insertIndex = i + 1;
    }
    List<String> artistSequence = new ArrayList<>();
    for (int i = 0; i < active.size(); i++) {
      if (i == insertIndex) artistSequence.add(artistName);
      artistSequence.add(resolveArtistName(active.get(i).getItem()));
    }
    if (insertIndex >= active.size()) artistSequence.add(artistName);

    int consecutive = 0;
    for (String name : artistSequence) {
      if (artistName.equalsIgnoreCase(name)) {
        if (++consecutive > maxConsecutive) return true;
      } else {
        consecutive = 0;
      }
    }
    return false;
  }

  private boolean violatesNoRepeatHours(
      UUID trackId, ListeningSessionEntity session, int noRepeatHours) {
    return playHistoryRepository.existsByTrackIdAndUserIdSince(
        trackId, session.getUser().getId(), OffsetDateTime.now().minusHours(noRepeatHours));
  }

  private String determineOutcome(int totalEdits, int approvedCount) {
    if (totalEdits == 0) return "NO_EDITS";
    if (approvedCount == 0) return "REJECTED";
    return approvedCount == totalEdits ? "APPLIED" : "PARTIAL";
  }

  public record ValidationResult(
      List<ValidatedEdit> approvedEdits, List<RejectedEdit> rejectedEdits, String outcome) {}

  public record ValidatedEdit(
      DjDecision.EditAction action, int position, UUID trackId, String reason, String intentLabel) {
    static ValidatedEdit from(DjDecision.QueueEdit edit) {
      return new ValidatedEdit(
          edit.action(), edit.position(), edit.trackId(), edit.reason(), edit.intentLabel());
    }
  }

  public record RejectedEdit(DjDecision.QueueEdit original, String rejectionReason) {}

  private record HardRules(int maxArtistConsecutive, int noRepeatTrackHours) {}
}
