package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdaptiveQueueManager {

  private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "PLAYING");

  private final AdaptiveQueueRepository adaptiveQueueRepository;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final AudioTrackRepository audioTrackRepository;

  public AdaptiveQueueManager(
      AdaptiveQueueRepository adaptiveQueueRepository,
      TrackFeaturesRepository trackFeaturesRepository,
      AudioTrackRepository audioTrackRepository) {
    this.adaptiveQueueRepository = adaptiveQueueRepository;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.audioTrackRepository = audioTrackRepository;
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

  private QueueTrackDto toQueueTrackDto(AdaptiveQueueEntity entry) {
    ItemEntity item = entry.getItem();
    return new QueueTrackDto(
        entry.getPosition(),
        item.getId(),
        item.getName(),
        resolveArtistName(item),
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

  static String resolveArtistName(ItemEntity item) {
    ItemEntity parent = item.getParent();
    if (parent == null) return null;
    ItemEntity grandparent = parent.getParent();
    return grandparent != null ? grandparent.getName() : parent.getName();
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
