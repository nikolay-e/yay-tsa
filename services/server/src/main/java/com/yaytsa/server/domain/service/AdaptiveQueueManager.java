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
import java.util.List;
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
    if (currentVersion != baseQueueVersion)
      return new QueueMutationResult(
          false,
          currentVersion,
          "Version conflict: expected " + baseQueueVersion + ", found " + currentVersion);
    ListeningSessionEntity session = listeningSessionRepository.findById(sessionId).orElse(null);
    if (session == null)
      return new QueueMutationResult(false, currentVersion, "Session not found: " + sessionId);

    long newVersion = currentVersion + 1;
    var activeQueue =
        adaptiveQueueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, ACTIVE_STATUSES);
    for (var edit : validated.approvedEdits()) {
      switch (edit.action()) {
        case INSERT -> applyInsert(edit, session, activeQueue, newVersion);
        case REMOVE -> applyEdit(edit, activeQueue, newVersion, true);
        case REORDER -> applyEdit(edit, activeQueue, newVersion, false);
      }
    }
    compactPositions(sessionId);
    return new QueueMutationResult(true, newVersion, null);
  }

  @Transactional(readOnly = true)
  public List<QueueTrackDto> getQueue(UUID sessionId) {
    return adaptiveQueueRepository
        .findBySessionIdAndStatusInOrderByPositionAsc(sessionId, ACTIVE_STATUSES)
        .stream()
        .map(this::toQueueTrackDto)
        .toList();
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
    var itemOpt = itemRepository.findById(edit.trackId());
    if (itemOpt.isEmpty()) {
      log.warn("Item not found during insert: {}", edit.trackId());
      return;
    }
    shiftPositionsFrom(activeQueue, edit.position());
    var entry = new AdaptiveQueueEntity();
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

  private void applyEdit(
      QueuePolicyValidator.ValidatedEdit edit,
      List<AdaptiveQueueEntity> activeQueue,
      long newVersion,
      boolean isRemove) {
    activeQueue.stream()
        .filter(
            e ->
                e.getItem().getId().equals(edit.trackId())
                    && ACTIVE_STATUSES.contains(e.getStatus()))
        .findFirst()
        .ifPresent(
            entry -> {
              if (isRemove) entry.setStatus("REMOVED");
              else entry.setPosition(edit.position());
              entry.setQueueVersion(newVersion);
              adaptiveQueueRepository.save(entry);
            });
  }

  private void shiftPositionsFrom(List<AdaptiveQueueEntity> activeQueue, int fromPosition) {
    for (var entry : activeQueue) {
      if (entry.getPosition() >= fromPosition && ACTIVE_STATUSES.contains(entry.getStatus())) {
        entry.setPosition(entry.getPosition() + 1);
        adaptiveQueueRepository.save(entry);
      }
    }
  }

  private void compactPositions(UUID sessionId) {
    var entries =
        adaptiveQueueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, ACTIVE_STATUSES);
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).getPosition() != i + 1) {
        entries.get(i).setPosition(i + 1);
        adaptiveQueueRepository.save(entries.get(i));
      }
    }
  }

  private QueueTrackDto toQueueTrackDto(AdaptiveQueueEntity entry) {
    ItemEntity item = entry.getItem();
    return new QueueTrackDto(
        entry.getPosition(),
        item.getId(),
        item.getName(),
        QueuePolicyValidator.resolveArtistName(item),
        item.getParent() != null ? item.getParent().getName() : null,
        audioTrackRepository
            .findById(item.getId())
            .map(AudioTrackEntity::getDurationMs)
            .orElse(null),
        entry.getStatus(),
        entry.getIntentLabel(),
        entry.getAddedReason(),
        trackFeaturesRepository
            .findByTrackId(item.getId())
            .map(
                f ->
                    new QueueTrackFeatures(
                        f.getBpm(),
                        f.getEnergy(),
                        f.getValence(),
                        f.getArousal(),
                        f.getDanceability()))
            .orElse(null));
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
