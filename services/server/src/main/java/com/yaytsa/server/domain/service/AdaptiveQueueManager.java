package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdaptiveQueueManager {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveQueueManager.class);
  private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "PLAYING");

  private final AdaptiveQueueRepository adaptiveQueueRepository;
  private final ItemRepository itemRepository;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final ListeningSessionRepository listeningSessionRepository;

  public AdaptiveQueueManager(
      AdaptiveQueueRepository adaptiveQueueRepository,
      ItemRepository itemRepository,
      TrackFeaturesRepository trackFeaturesRepository,
      AudioTrackRepository audioTrackRepository,
      ListeningSessionRepository listeningSessionRepository) {
    this.adaptiveQueueRepository = adaptiveQueueRepository;
    this.itemRepository = itemRepository;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.listeningSessionRepository = listeningSessionRepository;
  }

  @Transactional
  public QueueMutationResult applyDecision(
      UUID sessionId, long baseQueueVersion, QueuePolicyValidator.ValidationResult validated) {

    long currentVersion = getCurrentVersion(sessionId);
    if (currentVersion != baseQueueVersion) {
      return new QueueMutationResult(
          false,
          currentVersion,
          "Version conflict: expected " + baseQueueVersion + ", found " + currentVersion);
    }

    ListeningSessionEntity session = listeningSessionRepository.findById(sessionId).orElse(null);
    if (session == null) {
      return new QueueMutationResult(false, currentVersion, "Session not found: " + sessionId);
    }

    long newVersion = currentVersion + 1;
    List<AdaptiveQueueEntity> activeQueue =
        adaptiveQueueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, ACTIVE_STATUSES);

    for (QueuePolicyValidator.ValidatedEdit edit : validated.approvedEdits()) {
      switch (edit.action()) {
        case INSERT -> applyInsert(edit, session, activeQueue, newVersion);
        case REMOVE -> applyRemove(edit, activeQueue, newVersion);
        case REORDER -> applyReorder(edit, activeQueue, newVersion);
      }
    }

    compactPositions(sessionId);

    return new QueueMutationResult(true, newVersion, null);
  }

  @Transactional(readOnly = true)
  public List<QueueTrackDto> getQueue(UUID sessionId) {
    List<AdaptiveQueueEntity> entries =
        adaptiveQueueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, ACTIVE_STATUSES);

    List<QueueTrackDto> result = new ArrayList<>(entries.size());
    for (AdaptiveQueueEntity entry : entries) {
      result.add(toQueueTrackDto(entry));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public long getCurrentVersion(UUID sessionId) {
    return adaptiveQueueRepository.findMaxQueueVersionBySessionId(sessionId).orElse(0L);
  }

  private void applyInsert(
      QueuePolicyValidator.ValidatedEdit edit,
      ListeningSessionEntity session,
      List<AdaptiveQueueEntity> activeQueue,
      long newVersion) {

    Optional<ItemEntity> itemOpt = itemRepository.findById(edit.trackId());
    if (itemOpt.isEmpty()) {
      log.warn("Item not found during insert apply: {}", edit.trackId());
      return;
    }

    shiftPositionsFrom(activeQueue, edit.position());

    AdaptiveQueueEntity entry = new AdaptiveQueueEntity();
    entry.setSession(session);
    entry.setItem(itemOpt.get());
    entry.setPosition(edit.position());
    entry.setAddedReason(edit.reason());
    entry.setIntentLabel(edit.intentLabel());
    entry.setStatus("QUEUED");
    entry.setQueueVersion(newVersion);
    entry.setAddedAt(OffsetDateTime.now());

    adaptiveQueueRepository.save(entry);
    activeQueue.add(entry);
  }

  private void applyRemove(
      QueuePolicyValidator.ValidatedEdit edit,
      List<AdaptiveQueueEntity> activeQueue,
      long newVersion) {

    activeQueue.stream()
        .filter(
            e ->
                e.getItem().getId().equals(edit.trackId())
                    && ACTIVE_STATUSES.contains(e.getStatus()))
        .findFirst()
        .ifPresent(
            entry -> {
              entry.setStatus("REMOVED");
              entry.setQueueVersion(newVersion);
              adaptiveQueueRepository.save(entry);
            });
  }

  private void applyReorder(
      QueuePolicyValidator.ValidatedEdit edit,
      List<AdaptiveQueueEntity> activeQueue,
      long newVersion) {

    activeQueue.stream()
        .filter(
            e ->
                e.getItem().getId().equals(edit.trackId())
                    && ACTIVE_STATUSES.contains(e.getStatus()))
        .findFirst()
        .ifPresent(
            entry -> {
              entry.setPosition(edit.position());
              entry.setQueueVersion(newVersion);
              adaptiveQueueRepository.save(entry);
            });
  }

  private void shiftPositionsFrom(List<AdaptiveQueueEntity> activeQueue, int fromPosition) {
    for (AdaptiveQueueEntity entry : activeQueue) {
      if (entry.getPosition() >= fromPosition && ACTIVE_STATUSES.contains(entry.getStatus())) {
        entry.setPosition(entry.getPosition() + 1);
        adaptiveQueueRepository.save(entry);
      }
    }
  }

  private void compactPositions(UUID sessionId) {
    List<AdaptiveQueueEntity> activeEntries =
        adaptiveQueueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, ACTIVE_STATUSES);

    for (int i = 0; i < activeEntries.size(); i++) {
      if (activeEntries.get(i).getPosition() != i + 1) {
        activeEntries.get(i).setPosition(i + 1);
        adaptiveQueueRepository.save(activeEntries.get(i));
      }
    }
  }

  private QueueTrackDto toQueueTrackDto(AdaptiveQueueEntity entry) {
    ItemEntity item = entry.getItem();
    String artistName = resolveArtistName(item);
    String albumName = resolveAlbumName(item);
    Long durationMs = resolveDurationMs(item.getId());
    QueueTrackFeatures features = resolveFeatures(item.getId());

    return new QueueTrackDto(
        entry.getPosition(),
        item.getId(),
        item.getName(),
        artistName,
        albumName,
        durationMs,
        entry.getStatus(),
        entry.getIntentLabel(),
        entry.getAddedReason(),
        features);
  }

  private String resolveArtistName(ItemEntity item) {
    ItemEntity parent = item.getParent();
    if (parent == null) {
      return null;
    }
    ItemEntity grandparent = parent.getParent();
    return grandparent != null ? grandparent.getName() : parent.getName();
  }

  private String resolveAlbumName(ItemEntity item) {
    ItemEntity parent = item.getParent();
    return parent != null ? parent.getName() : null;
  }

  private Long resolveDurationMs(UUID trackId) {
    return audioTrackRepository.findById(trackId).map(AudioTrackEntity::getDurationMs).orElse(null);
  }

  private QueueTrackFeatures resolveFeatures(UUID trackId) {
    return trackFeaturesRepository
        .findByTrackId(trackId)
        .map(
            f ->
                new QueueTrackFeatures(
                    f.getBpm(), f.getEnergy(), f.getValence(), f.getArousal(), f.getDanceability()))
        .orElse(null);
  }

  public record QueueMutationResult(boolean success, long newVersion, String error) {}

  public record QueueTrackDto(
      int position,
      UUID trackId,
      String name,
      String artistName,
      String albumName,
      Long durationMs,
      String status,
      String intentLabel,
      String addedReason,
      QueueTrackFeatures features) {}

  public record QueueTrackFeatures(
      Float bpm, Float energy, Float valence, Float arousal, Float danceability) {}
}
