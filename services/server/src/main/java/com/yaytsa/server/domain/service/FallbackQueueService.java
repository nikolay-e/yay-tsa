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
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class FallbackQueueService {

  private static final int MAX_TRACKS_PER_ARTIST = 3;
  private static final Set<String> KNOWN_ROOT_GENRES =
      Set.of(
          "rock",
          "pop",
          "jazz",
          "blues",
          "metal",
          "electronic",
          "classical",
          "hip hop",
          "r&b",
          "country",
          "folk",
          "soul",
          "funk",
          "reggae",
          "punk",
          "latin",
          "ambient",
          "indie");

  private final RecommendationService recommendationService;
  private final CandidateRetrievalService candidateRetrievalService;
  private final RadioAnchorResolver radioAnchorResolver;
  private final AdaptiveQueueRepository queueRepository;
  private final ItemRepository itemRepository;
  private final PlaybackSignalRepository signalRepository;
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
      PlaybackSignalRepository signalRepository,
      TransactionTemplate transactionTemplate,
      @Value("${yaytsa.adaptive-dj.queue.max-size:40}") int defaultQueueSize,
      @Value("${yaytsa.adaptive-dj.queue.no-repeat-hours:6}") int noRepeatHours) {
    this.recommendationService = recommendationService;
    this.candidateRetrievalService = candidateRetrievalService;
    this.radioAnchorResolver = radioAnchorResolver;
    this.queueRepository = queueRepository;
    this.itemRepository = itemRepository;
    this.signalRepository = signalRepository;
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
    SessionSkipContext skipContext = buildSessionSkipContext(sessionId);

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
                avoidGenres,
                skipContext);
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
      if (randomIds.size() < needed && !effectiveGenres.isEmpty()) {
        Set<String> rootGenres = extractRootGenres(effectiveGenres);
        for (String root : rootGenres) {
          List<UUID> broadIds =
              itemRepository.findRandomAudioTrackIdsByGenreLike("%" + root + "%", needed * 2);
          broadIds.removeAll(excluded);
          Set<UUID> seen = new HashSet<>(randomIds);
          for (UUID id : broadIds) {
            if (seen.add(id)) randomIds.add(id);
          }
          if (randomIds.size() >= needed) break;
        }
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
            playingPosition,
            activeQueue));

    log.info(
        "Recommendation queue filled {} tracks for session {} (excluded {} recently played)",
        addedEntries.size(),
        sessionId,
        recentlyPlayed.size());
    return addedEntries;
  }

  private SessionSkipContext buildSessionSkipContext(UUID sessionId) {
    List<Object[]> rows = signalRepository.findSessionSkipContext(sessionId);
    if (rows.isEmpty()) return SessionSkipContext.EMPTY;

    Set<UUID> trackIds = new HashSet<>();
    Set<String> artistNames = new HashSet<>();
    float energySum = 0;
    float valenceSum = 0;
    int moodCount = 0;

    for (Object[] row : rows) {
      trackIds.add((UUID) row[0]);
      String artist = (String) row[1];
      if (artist != null && !artist.isEmpty()) artistNames.add(artist);
      Number energy = (Number) row[2];
      Number valence = (Number) row[3];
      if (energy != null && valence != null) {
        energySum += energy.floatValue();
        valenceSum += valence.floatValue();
        moodCount++;
      }
    }

    return new SessionSkipContext(
        trackIds,
        artistNames,
        moodCount > 0 ? energySum / moodCount : 0f,
        moodCount > 0 ? valenceSum / moodCount : 0f,
        moodCount > 0);
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

  private static String resolveArtistName(ItemEntity item) {
    try {
      ItemEntity album = item.getParent();
      if (album == null) return null;
      ItemEntity artist = album.getParent();
      return artist != null ? artist.getName() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static Set<String> extractRootGenres(List<String> genres) {
    Set<String> roots = new HashSet<>();
    for (String genre : genres) {
      String lower = genre.toLowerCase();
      for (String root : KNOWN_ROOT_GENRES) {
        if (lower.contains(root)) {
          roots.add(root);
          break;
        }
      }
    }
    return roots;
  }

  private List<AdaptiveQueueEntity> insertQueueEntries(
      ListeningSessionEntity session,
      List<ScoredTrack> recommendations,
      int maxPosition,
      long maxVersion,
      Set<UUID> existingTrackIds,
      int insertNextCount,
      int playingPosition,
      List<AdaptiveQueueEntity> activeQueue) {
    return transactionTemplate.execute(
        status -> {
          long newVersion = maxVersion + 1;
          List<UUID> itemIds =
              recommendations.stream().map(r -> r.candidate().id()).collect(Collectors.toList());
          Map<UUID, ItemEntity> itemsById =
              itemRepository.findAllById(itemIds).stream()
                  .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

          Map<String, Integer> artistCounts = new HashMap<>();
          for (var q : activeQueue) {
            String artist = resolveArtistName(q.getItem());
            if (artist != null) artistCounts.merge(artist, 1, Integer::sum);
          }

          Set<UUID> seen = new HashSet<>(existingTrackIds);
          List<ScoredTrack> deduped = new ArrayList<>();
          for (ScoredTrack rec : recommendations) {
            ItemEntity item = itemsById.get(rec.candidate().id());
            if (item != null && seen.add(item.getId())) {
              String artist = resolveArtistName(item);
              if (artist != null && artistCounts.getOrDefault(artist, 0) >= MAX_TRACKS_PER_ARTIST) {
                continue;
              }
              deduped.add(rec);
              if (artist != null) artistCounts.merge(artist, 1, Integer::sum);
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
