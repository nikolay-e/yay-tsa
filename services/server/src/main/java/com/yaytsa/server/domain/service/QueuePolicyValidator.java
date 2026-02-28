package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class QueuePolicyValidator {

  private static final Logger log = LoggerFactory.getLogger(QueuePolicyValidator.class);

  private static final int DEFAULT_MAX_ARTIST_CONSECUTIVE = 2;
  private static final int DEFAULT_NO_REPEAT_HOURS = 3;
  private static final int MAX_QUEUE_SIZE = 15;

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final ItemRepository itemRepository;
  private final PlaybackSignalRepository playbackSignalRepository;
  private final AdaptiveQueueRepository adaptiveQueueRepository;
  private final ObjectMapper objectMapper;

  public QueuePolicyValidator(
      TrackFeaturesRepository trackFeaturesRepository,
      ItemRepository itemRepository,
      PlaybackSignalRepository playbackSignalRepository,
      AdaptiveQueueRepository adaptiveQueueRepository,
      ObjectMapper objectMapper) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.itemRepository = itemRepository;
    this.playbackSignalRepository = playbackSignalRepository;
    this.adaptiveQueueRepository = adaptiveQueueRepository;
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
        case REMOVE -> validateRemove(edit, simulatedQueue, approvedEdits, rejectedEdits);
        case REORDER -> validateReorder(edit, simulatedQueue, approvedEdits, rejectedEdits);
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

    Optional<ItemEntity> itemOpt = itemRepository.findById(edit.trackId());
    if (itemOpt.isEmpty()) {
      rejected.add(new RejectedEdit(edit, "Track does not exist: " + edit.trackId()));
      return;
    }

    long activeCount = countActiveEntries(simulatedQueue);
    if (activeCount >= MAX_QUEUE_SIZE) {
      rejected.add(new RejectedEdit(edit, "Queue size limit reached (" + MAX_QUEUE_SIZE + ")"));
      return;
    }

    if (isDuplicateInQueue(edit.trackId(), simulatedQueue)) {
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

    if (artistName != null && hasAdjacentSameArtist(edit.position(), artistName, simulatedQueue)) {
      rejected.add(
          new RejectedEdit(edit, "Adjacent same-artist track not allowed for: " + artistName));
      return;
    }

    if (violatesNoRepeatHours(edit.trackId(), session, hardRules.noRepeatTrackHours)) {
      rejected.add(
          new RejectedEdit(
              edit, "Track played within last " + hardRules.noRepeatTrackHours + " hours"));
      return;
    }

    approved.add(
        new ValidatedEdit(
            edit.action(), edit.position(), edit.trackId(), edit.reason(), edit.intentLabel()));

    AdaptiveQueueEntity simulated = new AdaptiveQueueEntity();
    simulated.setItem(item);
    simulated.setPosition(edit.position());
    simulated.setStatus("QUEUED");
    simulatedQueue.add(simulated);
    simulatedQueue.sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
  }

  private void validateRemove(
      DjDecision.QueueEdit edit,
      List<AdaptiveQueueEntity> simulatedQueue,
      List<ValidatedEdit> approved,
      List<RejectedEdit> rejected) {

    boolean found =
        simulatedQueue.stream()
            .anyMatch(
                e ->
                    e.getItem().getId().equals(edit.trackId()) && !"REMOVED".equals(e.getStatus()));

    if (!found) {
      rejected.add(new RejectedEdit(edit, "Track not found in active queue: " + edit.trackId()));
      return;
    }

    approved.add(
        new ValidatedEdit(
            edit.action(), edit.position(), edit.trackId(), edit.reason(), edit.intentLabel()));

    simulatedQueue.stream()
        .filter(e -> e.getItem().getId().equals(edit.trackId()))
        .forEach(e -> e.setStatus("REMOVED"));
  }

  private void validateReorder(
      DjDecision.QueueEdit edit,
      List<AdaptiveQueueEntity> simulatedQueue,
      List<ValidatedEdit> approved,
      List<RejectedEdit> rejected) {

    boolean found =
        simulatedQueue.stream()
            .anyMatch(
                e ->
                    e.getItem().getId().equals(edit.trackId()) && !"REMOVED".equals(e.getStatus()));

    if (!found) {
      rejected.add(
          new RejectedEdit(edit, "Track not found in active queue for reorder: " + edit.trackId()));
      return;
    }

    approved.add(
        new ValidatedEdit(
            edit.action(), edit.position(), edit.trackId(), edit.reason(), edit.intentLabel()));
  }

  private HardRules parseHardRules(UserPreferenceContractEntity preferences) {
    if (preferences == null || preferences.getHardRules() == null) {
      return new HardRules(DEFAULT_MAX_ARTIST_CONSECUTIVE, DEFAULT_NO_REPEAT_HOURS);
    }

    try {
      Map<String, Object> rules =
          objectMapper.readValue(preferences.getHardRules(), new TypeReference<>() {});

      int maxConsecutive =
          getIntOrDefault(rules, "max_artist_consecutive", DEFAULT_MAX_ARTIST_CONSECUTIVE);
      int noRepeatHours = getIntOrDefault(rules, "no_repeat_track_hours", DEFAULT_NO_REPEAT_HOURS);

      return new HardRules(maxConsecutive, noRepeatHours);
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse hard_rules JSONB, using defaults", e);
      return new HardRules(DEFAULT_MAX_ARTIST_CONSECUTIVE, DEFAULT_NO_REPEAT_HOURS);
    }
  }

  private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
    Object val = map.get(key);
    if (val instanceof Number n) {
      return n.intValue();
    }
    return defaultValue;
  }

  private long countActiveEntries(List<AdaptiveQueueEntity> queue) {
    return queue.stream().filter(e -> !"REMOVED".equals(e.getStatus())).count();
  }

  private boolean isDuplicateInQueue(UUID trackId, List<AdaptiveQueueEntity> queue) {
    return queue.stream()
        .anyMatch(e -> e.getItem().getId().equals(trackId) && !"REMOVED".equals(e.getStatus()));
  }

  private String resolveArtistName(ItemEntity item) {
    ItemEntity parent = item.getParent();
    if (parent == null) {
      return null;
    }
    ItemEntity grandparent = parent.getParent();
    if (grandparent != null) {
      return grandparent.getName();
    }
    return parent.getName();
  }

  private boolean violatesConsecutiveArtistLimit(
      int insertPosition, String artistName, List<AdaptiveQueueEntity> queue, int maxConsecutive) {

    List<AdaptiveQueueEntity> active =
        queue.stream()
            .filter(e -> !"REMOVED".equals(e.getStatus()))
            .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
            .toList();

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
      if (i == insertIndex) {
        artistSequence.add(artistName);
      }
      String existingArtist = resolveArtistName(active.get(i).getItem());
      artistSequence.add(existingArtist);
    }
    if (insertIndex >= active.size()) {
      artistSequence.add(artistName);
    }

    int consecutive = 0;
    for (String name : artistSequence) {
      if (artistName.equalsIgnoreCase(name)) {
        consecutive++;
        if (consecutive > maxConsecutive) {
          return true;
        }
      } else {
        consecutive = 0;
      }
    }

    return false;
  }

  private boolean hasAdjacentSameArtist(
      int insertPosition, String artistName, List<AdaptiveQueueEntity> queue) {

    List<AdaptiveQueueEntity> active =
        queue.stream()
            .filter(e -> !"REMOVED".equals(e.getStatus()))
            .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
            .toList();

    AdaptiveQueueEntity before = null;
    AdaptiveQueueEntity after = null;

    for (int i = 0; i < active.size(); i++) {
      if (active.get(i).getPosition() >= insertPosition) {
        after = active.get(i);
        if (i > 0) {
          before = active.get(i - 1);
        }
        break;
      }
      before = active.get(i);
    }

    if (before != null) {
      String beforeArtist = resolveArtistName(before.getItem());
      if (artistName.equalsIgnoreCase(beforeArtist)) {
        return true;
      }
    }

    if (after != null) {
      String afterArtist = resolveArtistName(after.getItem());
      if (artistName.equalsIgnoreCase(afterArtist)) {
        return true;
      }
    }

    return false;
  }

  private boolean violatesNoRepeatHours(
      UUID trackId, ListeningSessionEntity session, int noRepeatHours) {

    List<PlaybackSignalEntity> recentSignals =
        playbackSignalRepository.findBySessionIdOrderByCreatedAtDesc(
            session.getId(), PageRequest.of(0, 200));

    OffsetDateTime cutoff = OffsetDateTime.now().minusHours(noRepeatHours);

    return recentSignals.stream()
        .anyMatch(
            s ->
                s.getItem().getId().equals(trackId)
                    && "PLAY_START".equals(s.getSignalType())
                    && s.getCreatedAt().isAfter(cutoff));
  }

  private String determineOutcome(int totalEdits, int approvedCount) {
    if (approvedCount == 0) {
      return "REJECTED";
    }
    if (approvedCount == totalEdits) {
      return "APPLIED";
    }
    return "PARTIAL";
  }

  public record ValidationResult(
      List<ValidatedEdit> approvedEdits, List<RejectedEdit> rejectedEdits, String outcome) {}

  public record ValidatedEdit(
      DjDecision.EditAction action,
      int position,
      UUID trackId,
      String reason,
      String intentLabel) {}

  public record RejectedEdit(DjDecision.QueueEdit original, String rejectionReason) {}

  private record HardRules(int maxArtistConsecutive, int noRepeatTrackHours) {}
}
