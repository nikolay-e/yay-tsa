package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FallbackQueueService {

  private static final Logger log = LoggerFactory.getLogger(FallbackQueueService.class);
  private static final int SIMILARITY_CANDIDATES = 20;
  private static final int RANDOM_CANDIDATES = 30;

  private final CandidateRetrievalService candidateRetrievalService;
  private final AdaptiveQueueRepository queueRepository;
  private final ItemRepository itemRepository;
  private final PlayStateRepository playStateRepository;

  private final int targetQueueSize;

  public FallbackQueueService(
      CandidateRetrievalService candidateRetrievalService,
      AdaptiveQueueRepository queueRepository,
      ItemRepository itemRepository,
      PlayStateRepository playStateRepository,
      @Value("${yaytsa.adaptive-dj.queue.max-size:15}") int targetQueueSize) {
    this.candidateRetrievalService = candidateRetrievalService;
    this.queueRepository = queueRepository;
    this.itemRepository = itemRepository;
    this.playStateRepository = playStateRepository;
    this.targetQueueSize = targetQueueSize;
  }

  @Transactional
  public List<AdaptiveQueueEntity> fillQueue(ListeningSessionEntity session, int currentQueueSize) {
    int needed = targetQueueSize - currentQueueSize;
    if (needed <= 0) {
      return List.of();
    }

    UUID userId = session.getUser().getId();
    UUID sessionId = session.getId();

    Set<UUID> existingTrackIds =
        queueRepository
            .findBySessionIdAndStatusInOrderByPositionAsc(sessionId, List.of("QUEUED", "PLAYING"))
            .stream()
            .map(q -> q.getItem().getId())
            .collect(Collectors.toSet());

    Set<UUID> overplayed = Set.copyOf(candidateRetrievalService.getRecentlyOverplayed(userId, 48));

    List<UUID> selectedTrackIds = new ArrayList<>();

    List<UUID> similarTracks = findSimilarToRecentlyPlayed(userId, existingTrackIds, overplayed);
    addUniqueUpTo(selectedTrackIds, similarTracks, needed, existingTrackIds, overplayed);

    if (selectedTrackIds.size() < needed) {
      List<UUID> randomFavorites = findRandomFavorites(userId, existingTrackIds, overplayed);
      addUniqueUpTo(
          selectedTrackIds,
          randomFavorites,
          needed - selectedTrackIds.size(),
          existingTrackIds,
          overplayed);
    }

    if (selectedTrackIds.isEmpty()) {
      log.debug("Fallback queue: no candidates found for session {}", sessionId);
      return List.of();
    }

    long maxVersion =
        queueRepository
            .findBySessionIdAndStatusInOrderByPositionAsc(sessionId, List.of("QUEUED", "PLAYING"))
            .stream()
            .mapToLong(AdaptiveQueueEntity::getQueueVersion)
            .max()
            .orElse(0);
    long newVersion = maxVersion + 1;

    int maxPosition =
        queueRepository
            .findBySessionIdAndStatusInOrderByPositionAsc(sessionId, List.of("QUEUED", "PLAYING"))
            .stream()
            .mapToInt(AdaptiveQueueEntity::getPosition)
            .max()
            .orElse(0);

    List<AdaptiveQueueEntity> addedEntries = new ArrayList<>();
    for (int i = 0; i < selectedTrackIds.size(); i++) {
      ItemEntity item = itemRepository.findById(selectedTrackIds.get(i)).orElse(null);
      if (item == null) {
        continue;
      }

      var entry = new AdaptiveQueueEntity();
      entry.setSession(session);
      entry.setItem(item);
      entry.setPosition(maxPosition + i + 1);
      entry.setAddedReason("fallback");
      entry.setIntentLabel("auto-fill");
      entry.setStatus("QUEUED");
      entry.setQueueVersion(newVersion);
      entry.setAddedAt(OffsetDateTime.now());
      addedEntries.add(queueRepository.save(entry));
    }

    log.info(
        "Fallback queue filled {} tracks for session {} (similarity={}, favorites={})",
        addedEntries.size(),
        sessionId,
        Math.min(similarTracks.size(), needed),
        Math.max(0, addedEntries.size() - similarTracks.size()));

    return addedEntries;
  }

  private List<UUID> findSimilarToRecentlyPlayed(
      UUID userId, Set<UUID> existingTrackIds, Set<UUID> overplayed) {
    var recentlyPlayed = itemRepository.findRecentlyPlayedByUser(userId, PageRequest.of(0, 5));

    if (recentlyPlayed.isEmpty()) {
      return List.of();
    }

    List<UUID> candidates = new ArrayList<>();
    for (var recentItem : recentlyPlayed) {
      var similar =
          candidateRetrievalService.findSimilarTracks(recentItem.getId(), SIMILARITY_CANDIDATES);
      for (var track : similar) {
        UUID trackId = track.id();
        if (!existingTrackIds.contains(trackId) && !overplayed.contains(trackId)) {
          candidates.add(trackId);
        }
      }
    }

    Collections.shuffle(candidates);
    return candidates;
  }

  private List<UUID> findRandomFavorites(
      UUID userId, Set<UUID> existingTrackIds, Set<UUID> overplayed) {
    var favorites = playStateRepository.findAllByUserIdAndIsFavoriteTrue(userId);

    List<UUID> candidateIds =
        favorites.stream()
            .map(ps -> ps.getItem().getId())
            .filter(id -> !existingTrackIds.contains(id) && !overplayed.contains(id))
            .collect(Collectors.toCollection(ArrayList::new));

    Collections.shuffle(candidateIds);
    return candidateIds;
  }

  private void addUniqueUpTo(
      List<UUID> target,
      List<UUID> source,
      int maxToAdd,
      Set<UUID> existingTrackIds,
      Set<UUID> overplayed) {
    int added = 0;
    for (UUID id : source) {
      if (added >= maxToAdd) {
        break;
      }
      if (!target.contains(id) && !existingTrackIds.contains(id) && !overplayed.contains(id)) {
        target.add(id);
        added++;
      }
    }
  }
}
