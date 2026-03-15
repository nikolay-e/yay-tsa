package com.yaytsa.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.domain.service.RecommendationService.RecommendationContext;
import com.yaytsa.server.domain.service.RecommendationService.ScoredTrack;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FallbackQueueService {

  private static final Logger log = LoggerFactory.getLogger(FallbackQueueService.class);

  private final RecommendationService recommendationService;
  private final CandidateRetrievalService candidateRetrievalService;
  private final AdaptiveQueueRepository queueRepository;
  private final ItemRepository itemRepository;
  private final int targetQueueSize;
  private final int noRepeatHours;

  private final Cache<UUID, ReentrantLock> sessionFillLocks =
      Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(500).build();

  public FallbackQueueService(
      RecommendationService recommendationService,
      CandidateRetrievalService candidateRetrievalService,
      AdaptiveQueueRepository queueRepository,
      ItemRepository itemRepository,
      @Value("${yaytsa.adaptive-dj.queue.max-size:50}") int targetQueueSize,
      @Value("${yaytsa.adaptive-dj.queue.no-repeat-hours:6}") int noRepeatHours) {
    this.recommendationService = recommendationService;
    this.candidateRetrievalService = candidateRetrievalService;
    this.queueRepository = queueRepository;
    this.itemRepository = itemRepository;
    this.targetQueueSize = targetQueueSize;
    this.noRepeatHours = noRepeatHours;
  }

  public List<AdaptiveQueueEntity> fillQueue(ListeningSessionEntity session) {
    return fillQueue(session, 0.5f, 0.5f, 0.3f, Set.of());
  }

  public List<AdaptiveQueueEntity> fillQueue(
      ListeningSessionEntity session,
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      Set<String> avoidArtists) {
    UUID sessionId = session.getId();
    ReentrantLock lock = sessionFillLocks.get(sessionId, k -> new ReentrantLock());
    lock.lock();
    try {
      return fillQueueUnderLock(
          session, targetEnergy, targetValence, explorationWeight, avoidArtists);
    } finally {
      lock.unlock();
    }
  }

  private List<AdaptiveQueueEntity> fillQueueUnderLock(
      ListeningSessionEntity session,
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      Set<String> avoidArtists) {
    UUID userId = session.getUser().getId();
    UUID sessionId = session.getId();

    var activeQueue =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, List.of("QUEUED", "PLAYING"));

    if (activeQueue.size() >= targetQueueSize) {
      return List.of();
    }
    int needed = targetQueueSize - activeQueue.size();

    Set<UUID> existingTrackIds =
        activeQueue.stream().map(q -> q.getItem().getId()).collect(Collectors.toSet());

    Set<UUID> overplayed = Set.copyOf(candidateRetrievalService.getRecentlyOverplayed(userId, 48));
    Set<UUID> recentlyPlayed =
        new HashSet<>(candidateRetrievalService.getRecentlyPlayedTrackIds(userId, noRepeatHours));

    Set<UUID> excluded = new HashSet<>(overplayed);
    excluded.addAll(recentlyPlayed);
    excluded.addAll(existingTrackIds);

    UUID lastPlayedTrackId = null;
    if (!activeQueue.isEmpty()) {
      var playing = activeQueue.stream().filter(e -> "PLAYING".equals(e.getStatus())).findFirst();
      lastPlayedTrackId = playing.map(e -> e.getItem().getId()).orElse(null);
    }

    var ctx =
        new RecommendationContext(
            targetEnergy,
            targetValence,
            explorationWeight,
            lastPlayedTrackId,
            recentlyPlayed,
            overplayed,
            excluded,
            avoidArtists);

    List<ScoredTrack> recommendations = recommendationService.recommend(userId, ctx, needed);

    if (recommendations.isEmpty()) {
      log.warn("Recommendation pipeline returned no results for session {}", sessionId);
      return List.of();
    }

    long maxVersion = queueRepository.findMaxQueueVersionBySessionId(sessionId).orElse(0L);
    int maxPosition =
        activeQueue.stream().mapToInt(AdaptiveQueueEntity::getPosition).max().orElse(0);

    List<AdaptiveQueueEntity> addedEntries =
        insertQueueEntries(session, recommendations, maxPosition, maxVersion);

    log.info(
        "Recommendation queue filled {} tracks for session {} (excluded {} recently played)",
        addedEntries.size(),
        sessionId,
        recentlyPlayed.size());
    return addedEntries;
  }

  @Transactional
  List<AdaptiveQueueEntity> insertQueueEntries(
      ListeningSessionEntity session,
      List<ScoredTrack> recommendations,
      int maxPosition,
      long maxVersion) {
    long newVersion = maxVersion + 1;
    List<UUID> itemIds =
        recommendations.stream().map(r -> r.candidate().id()).collect(Collectors.toList());
    Map<UUID, ItemEntity> itemsById =
        itemRepository.findAllById(itemIds).stream()
            .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

    List<AdaptiveQueueEntity> toSave = new ArrayList<>();
    int positionOffset = 0;
    for (ScoredTrack rec : recommendations) {
      ItemEntity item = itemsById.get(rec.candidate().id());
      if (item == null) continue;
      var entry = new AdaptiveQueueEntity();
      entry.setSession(session);
      entry.setItem(item);
      entry.setPosition(maxPosition + positionOffset + 1);
      entry.setAddedReason(rec.source());
      entry.setIntentLabel("recommendation");
      entry.setStatus("QUEUED");
      entry.setQueueVersion(newVersion);
      entry.setAddedAt(OffsetDateTime.now());
      toSave.add(entry);
      positionOffset++;
    }
    return queueRepository.saveAll(toSave);
  }
}
