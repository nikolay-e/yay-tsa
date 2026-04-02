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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FallbackQueueService {

  private static final Logger log = LoggerFactory.getLogger(FallbackQueueService.class);

  private final RecommendationService recommendationService;
  private final CandidateRetrievalService candidateRetrievalService;
  private final RadioAnchorResolver radioAnchorResolver;
  private final AdaptiveQueueRepository queueRepository;
  private final ItemRepository itemRepository;
  private final TransactionTemplate transactionTemplate;
  private final int defaultQueueSize;
  private final int noRepeatHours;

  private final Cache<UUID, ReentrantLock> sessionFillLocks =
      Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(500).build();

  public FallbackQueueService(
      RecommendationService recommendationService,
      CandidateRetrievalService candidateRetrievalService,
      RadioAnchorResolver radioAnchorResolver,
      AdaptiveQueueRepository queueRepository,
      ItemRepository itemRepository,
      TransactionTemplate transactionTemplate,
      @Value("${yaytsa.adaptive-dj.queue.max-size:40}") int defaultQueueSize,
      @Value("${yaytsa.adaptive-dj.queue.no-repeat-hours:6}") int noRepeatHours) {
    this.recommendationService = recommendationService;
    this.candidateRetrievalService = candidateRetrievalService;
    this.radioAnchorResolver = radioAnchorResolver;
    this.queueRepository = queueRepository;
    this.itemRepository = itemRepository;
    this.transactionTemplate = transactionTemplate;
    this.defaultQueueSize = defaultQueueSize;
    this.noRepeatHours = noRepeatHours;
  }

  public List<AdaptiveQueueEntity> fillQueue(ListeningSessionEntity session) {
    return fillQueue(
        session, 0.5f, 0.5f, 0.3f, Set.of(), List.of(), List.of(), defaultQueueSize, 0);
  }

  public List<AdaptiveQueueEntity> fillQueue(
      ListeningSessionEntity session,
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      Set<String> avoidArtists,
      List<String> preferGenres,
      List<String> avoidGenres,
      int dynamicQueueSize,
      int insertNextCount) {
    UUID sessionId = session.getId();
    ReentrantLock lock = sessionFillLocks.get(sessionId, k -> new ReentrantLock());
    lock.lock();
    try {
      return fillQueueUnderLock(
          session,
          targetEnergy,
          targetValence,
          explorationWeight,
          avoidArtists,
          preferGenres,
          avoidGenres,
          dynamicQueueSize,
          insertNextCount);
    } finally {
      lock.unlock();
    }
  }

  private List<AdaptiveQueueEntity> fillQueueUnderLock(
      ListeningSessionEntity session,
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      Set<String> avoidArtists,
      List<String> preferGenres,
      List<String> avoidGenres,
      int dynamicQueueSize,
      int insertNextCount) {
    UUID userId = session.getUser().getId();
    UUID sessionId = session.getId();

    var activeQueue =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, List.of("QUEUED", "PLAYING"));

    if (activeQueue.size() >= dynamicQueueSize) {
      return List.of();
    }

    boolean isFirstFill = activeQueue.isEmpty();
    UUID seedTrackId = session.getSeedTrackId();

    int needed = dynamicQueueSize - activeQueue.size();
    if (isFirstFill && session.isRadioMode() && seedTrackId != null) {
      needed = Math.max(0, needed - 1);
    }

    Set<UUID> existingTrackIds =
        activeQueue.stream().map(q -> q.getItem().getId()).collect(Collectors.toSet());

    Set<UUID> overplayed = Set.copyOf(candidateRetrievalService.getRecentlyOverplayed(userId, 48));
    Set<UUID> recentlyPlayed =
        new HashSet<>(candidateRetrievalService.getRecentlyPlayedTrackIds(userId, noRepeatHours));

    Set<UUID> excluded = new HashSet<>(overplayed);
    excluded.addAll(recentlyPlayed);
    excluded.addAll(existingTrackIds);
    if (seedTrackId != null) {
      excluded.add(seedTrackId);
    }

    UUID lastPlayedTrackId = null;
    if (!activeQueue.isEmpty()) {
      var playing = activeQueue.stream().filter(e -> "PLAYING".equals(e.getStatus())).findFirst();
      lastPlayedTrackId = playing.map(e -> e.getItem().getId()).orElse(null);
    }
    if (lastPlayedTrackId == null && session.isRadioMode() && seedTrackId != null) {
      lastPlayedTrackId = seedTrackId;
    }

    List<String> effectiveGenres = mergeGenres(preferGenres, session.getSeedGenreList());

    var ctx =
        RecommendationContext.standard(
            targetEnergy,
            targetValence,
            explorationWeight,
            lastPlayedTrackId,
            recentlyPlayed,
            overplayed,
            excluded,
            avoidArtists,
            effectiveGenres,
            avoidGenres);

    if (session.isRadioMode()) {
      float[] anchor = radioAnchorResolver.resolveAnchorEmbedding(session);
      float weight = radioAnchorResolver.computeWeight(session.getId());
      if (anchor != null) {
        ctx =
            RecommendationContext.radio(
                ctx.targetEnergy(),
                ctx.targetValence(),
                ctx.explorationWeight(),
                ctx.lastPlayedTrackId(),
                ctx.recentlyPlayedIds(),
                ctx.overplayedIds(),
                ctx.excludeIds(),
                ctx.avoidArtists(),
                anchor,
                weight,
                effectiveGenres,
                avoidGenres);
      }
    }

    List<ScoredTrack> recommendations =
        needed > 0 ? recommendationService.recommend(userId, ctx, needed) : List.of();

    if (needed > 0 && recommendations.isEmpty()) {
      log.warn(
          "Recommendation pipeline returned no results for session {}, using random fallback",
          sessionId);
      List<UUID> randomIds = new ArrayList<>();
      if (!effectiveGenres.isEmpty()) {
        List<String> lowercased = effectiveGenres.stream().map(String::toLowerCase).toList();
        randomIds.addAll(itemRepository.findRandomAudioTrackIdsByGenre(lowercased, needed * 2));
        randomIds.removeAll(excluded);
      }
      if (randomIds.size() < needed) {
        List<UUID> generalIds = new ArrayList<>(itemRepository.findRandomAudioTrackIds(needed * 2));
        generalIds.removeAll(excluded);
        Set<UUID> seen = new HashSet<>(randomIds);
        for (UUID id : generalIds) {
          if (seen.add(id)) randomIds.add(id);
        }
      }
      if (randomIds.isEmpty()) {
        return List.of();
      }
      recommendations =
          randomIds.stream()
              .map(
                  id ->
                      new ScoredTrack(
                          new CandidateRetrievalService.TrackCandidate(
                              id, null, null, null, null, null, null, null, null, null, null, null),
                          0.5,
                          "random_fallback",
                          0L))
              .limit(needed)
              .toList();
    }

    long maxVersion = queueRepository.findMaxQueueVersionBySessionId(sessionId).orElse(0L);
    int maxPosition =
        activeQueue.stream().mapToInt(AdaptiveQueueEntity::getPosition).max().orElse(0);

    List<AdaptiveQueueEntity> addedEntries = new ArrayList<>();

    if (isFirstFill && session.isRadioMode() && seedTrackId != null) {
      ItemEntity seedItem = itemRepository.findById(seedTrackId).orElse(null);
      if (seedItem != null) {
        var seedEntry = new AdaptiveQueueEntity();
        seedEntry.setSession(session);
        seedEntry.setItem(seedItem);
        seedEntry.setPosition(maxPosition + 1);
        seedEntry.setAddedReason("radio_seed");
        seedEntry.setIntentLabel("seed");
        seedEntry.setStatus("QUEUED");
        seedEntry.setQueueVersion(maxVersion + 1);
        seedEntry.setAddedAt(OffsetDateTime.now());
        addedEntries.add(queueRepository.save(seedEntry));
        maxPosition++;
        maxVersion++;
      }
    }

    int playingPosition =
        activeQueue.stream()
            .filter(e -> "PLAYING".equals(e.getStatus()))
            .mapToInt(AdaptiveQueueEntity::getPosition)
            .findFirst()
            .orElse(-1);

    addedEntries.addAll(
        insertQueueEntries(
            session,
            recommendations,
            maxPosition,
            maxVersion,
            existingTrackIds,
            insertNextCount,
            playingPosition));

    log.info(
        "Recommendation queue filled {} tracks for session {} (excluded {} recently played)",
        addedEntries.size(),
        sessionId,
        recentlyPlayed.size());
    return addedEntries;
  }

  private static List<String> mergeGenres(List<String> llmGenres, List<String> seedGenres) {
    Set<String> seen = new HashSet<>();
    List<String> merged = new ArrayList<>();
    for (String g : llmGenres != null ? llmGenres : List.<String>of()) {
      if (seen.add(g.toLowerCase())) merged.add(g);
    }
    for (String g : seedGenres != null ? seedGenres : List.<String>of()) {
      if (seen.add(g.toLowerCase())) merged.add(g);
    }
    return merged;
  }

  private List<AdaptiveQueueEntity> insertQueueEntries(
      ListeningSessionEntity session,
      List<ScoredTrack> recommendations,
      int maxPosition,
      long maxVersion,
      Set<UUID> existingTrackIds,
      int insertNextCount,
      int playingPosition) {
    return transactionTemplate.execute(
        status -> {
          long newVersion = maxVersion + 1;
          List<UUID> itemIds =
              recommendations.stream().map(r -> r.candidate().id()).collect(Collectors.toList());
          Map<UUID, ItemEntity> itemsById =
              itemRepository.findAllById(itemIds).stream()
                  .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

          Set<UUID> seen = new HashSet<>(existingTrackIds);
          List<ScoredTrack> deduped = new ArrayList<>();
          for (ScoredTrack rec : recommendations) {
            ItemEntity item = itemsById.get(rec.candidate().id());
            if (item != null && seen.add(item.getId())) {
              deduped.add(rec);
            }
          }

          int effectiveInsertNext =
              (playingPosition >= 0) ? Math.min(insertNextCount, deduped.size()) : 0;

          List<ScoredTrack> playNextTracks = deduped.subList(0, effectiveInsertNext);
          List<ScoredTrack> appendTracks = deduped.subList(effectiveInsertNext, deduped.size());

          List<AdaptiveQueueEntity> toSave = new ArrayList<>();

          if (!playNextTracks.isEmpty()) {
            queueRepository.shiftPositionsAfter(
                session.getId(), playingPosition, playNextTracks.size());

            int insertPos = playingPosition + 1;
            for (ScoredTrack rec : playNextTracks) {
              var entry = new AdaptiveQueueEntity();
              entry.setSession(session);
              entry.setItem(itemsById.get(rec.candidate().id()));
              entry.setPosition(insertPos++);
              entry.setAddedReason(rec.source());
              entry.setIntentLabel("play_next");
              entry.setStatus("QUEUED");
              entry.setQueueVersion(newVersion);
              entry.setAddedAt(OffsetDateTime.now());
              toSave.add(entry);
            }
          }

          int appendPosition = maxPosition + (playNextTracks.isEmpty() ? 0 : playNextTracks.size());
          for (ScoredTrack rec : appendTracks) {
            var entry = new AdaptiveQueueEntity();
            entry.setSession(session);
            entry.setItem(itemsById.get(rec.candidate().id()));
            entry.setPosition(++appendPosition);
            entry.setAddedReason(rec.source());
            entry.setIntentLabel("recommendation");
            entry.setStatus("QUEUED");
            entry.setQueueVersion(newVersion);
            entry.setAddedAt(OffsetDateTime.now());
            toSave.add(entry);
          }

          return queueRepository.saveAll(toSave);
        });
  }
}
