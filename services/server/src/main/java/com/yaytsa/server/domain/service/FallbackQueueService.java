package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
  private static final float ENERGY_BAND = 0.15f;
  private static final float POST_FILTER_ENERGY_BAND = 0.25f;
  private static final float MAX_FAVORITES_RATIO = 0.3f;
  private static final int RANDOM_OVERSAMPLE_FACTOR = 4;

  private final CandidateRetrievalService candidateRetrievalService;
  private final AdaptiveQueueRepository queueRepository;
  private final ItemRepository itemRepository;
  private final PlayStateRepository playStateRepository;
  private final int targetQueueSize;
  private final int noRepeatHours;

  public FallbackQueueService(
      CandidateRetrievalService candidateRetrievalService,
      AdaptiveQueueRepository queueRepository,
      ItemRepository itemRepository,
      PlayStateRepository playStateRepository,
      @Value("${yaytsa.adaptive-dj.queue.max-size:50}") int targetQueueSize,
      @Value("${yaytsa.adaptive-dj.queue.no-repeat-hours:6}") int noRepeatHours) {
    this.candidateRetrievalService = candidateRetrievalService;
    this.queueRepository = queueRepository;
    this.itemRepository = itemRepository;
    this.playStateRepository = playStateRepository;
    this.targetQueueSize = targetQueueSize;
    this.noRepeatHours = noRepeatHours;
  }

  @Transactional
  public List<AdaptiveQueueEntity> fillQueue(ListeningSessionEntity session, int currentQueueSize) {
    int needed = targetQueueSize - currentQueueSize;
    if (needed <= 0) return List.of();

    UUID userId = session.getUser().getId();
    UUID sessionId = session.getId();

    var activeQueue =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            sessionId, List.of("QUEUED", "PLAYING"));
    Set<UUID> existingTrackIds =
        activeQueue.stream().map(q -> q.getItem().getId()).collect(Collectors.toSet());
    long maxVersion =
        activeQueue.stream().mapToLong(AdaptiveQueueEntity::getQueueVersion).max().orElse(0);
    int maxPosition =
        activeQueue.stream().mapToInt(AdaptiveQueueEntity::getPosition).max().orElse(0);

    Set<UUID> overplayed = Set.copyOf(candidateRetrievalService.getRecentlyOverplayed(userId, 48));
    Set<UUID> recentlyPlayed =
        new HashSet<>(candidateRetrievalService.getRecentlyPlayedTrackIds(userId, noRepeatHours));
    Set<UUID> excluded = new HashSet<>(overplayed);
    excluded.addAll(recentlyPlayed);

    List<UUID> selectedTrackIds = new ArrayList<>();

    List<UUID> similarTracks =
        findSimilarToRecentlyPlayed(userId, session, existingTrackIds, excluded);
    addUniqueUpTo(selectedTrackIds, similarTracks, needed, existingTrackIds, excluded);

    if (selectedTrackIds.size() < needed) {
      boolean discovery = hasDiscoveryTag(session);
      List<UUID> featureTracks =
          discovery
              ? findNeverPlayedWithFeatures(userId, session, needed - selectedTrackIds.size())
              : findBySessionFeatures(session, needed - selectedTrackIds.size());
      addUniqueUpTo(
          selectedTrackIds,
          featureTracks,
          needed - selectedTrackIds.size(),
          existingTrackIds,
          excluded);
    }

    if (selectedTrackIds.size() < needed) {
      int maxFavorites = Math.max(1, (int) (needed * MAX_FAVORITES_RATIO));
      int favoritesToAdd = Math.min(maxFavorites, needed - selectedTrackIds.size());
      var randomFavorites = findRandomFavorites(userId, existingTrackIds, excluded);
      addUniqueUpTo(selectedTrackIds, randomFavorites, favoritesToAdd, existingTrackIds, excluded);
    }

    if (selectedTrackIds.size() < needed) {
      int remaining = needed - selectedTrackIds.size();
      var randomTracks =
          findRandomTracks(existingTrackIds, excluded, remaining * RANDOM_OVERSAMPLE_FACTOR);
      addUniqueUpTo(selectedTrackIds, randomTracks, remaining, existingTrackIds, excluded);
    }

    if (selectedTrackIds.size() < needed) {
      int remaining = needed - selectedTrackIds.size();
      log.warn(
          "All tiers exhausted with exclusions for session {}, filling {} tracks without repeat"
              + " filter",
          sessionId,
          remaining);
      var lastResort = itemRepository.findRandomAudioTrackIds(remaining * RANDOM_OVERSAMPLE_FACTOR);
      addUniqueUpTo(selectedTrackIds, lastResort, remaining, existingTrackIds, Set.of());
    }

    if (selectedTrackIds.isEmpty()) {
      log.warn("Fallback queue: no candidates found for session {}", sessionId);
      return List.of();
    }

    long newVersion = maxVersion + 1;
    List<AdaptiveQueueEntity> addedEntries = new ArrayList<>();
    for (int i = 0; i < selectedTrackIds.size(); i++) {
      ItemEntity item = itemRepository.findById(selectedTrackIds.get(i)).orElse(null);
      if (item == null) continue;
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
        "Fallback queue filled {} tracks for session {} (excluded {} recently played)",
        addedEntries.size(),
        sessionId,
        recentlyPlayed.size());
    return addedEntries;
  }

  private List<UUID> findSimilarToRecentlyPlayed(
      UUID userId, ListeningSessionEntity session, Set<UUID> existingTrackIds, Set<UUID> excluded) {
    var recentlyPlayed = itemRepository.findRecentlyPlayedByUser(userId, PageRequest.of(0, 5));
    if (recentlyPlayed.isEmpty()) return List.of();
    List<UUID> candidates = new ArrayList<>();
    for (var recentItem : recentlyPlayed) {
      var similar =
          candidateRetrievalService.findSimilarTracks(recentItem.getId(), SIMILARITY_CANDIDATES);
      for (var track : similar) {
        UUID trackId = track.id();
        if (!existingTrackIds.contains(trackId) && !excluded.contains(trackId))
          candidates.add(trackId);
      }
    }
    candidates = filterBySessionFeatures(candidates, session);
    Collections.shuffle(candidates);
    return candidates;
  }

  private List<UUID> findBySessionFeatures(ListeningSessionEntity session, int limit) {
    Float energyNorm = normalizeEnergy(session.getEnergy());
    Float arousalNorm = normalizeEnergy(session.getIntensity());

    var filters =
        new CandidateRetrievalService.LibrarySearchFilters(
            energyNorm != null ? Math.max(0f, energyNorm - ENERGY_BAND) : null,
            energyNorm != null ? Math.min(1f, energyNorm + ENERGY_BAND) : null,
            null,
            null,
            energyNorm != null && energyNorm > 0.6f ? 0.4f : null,
            null,
            arousalNorm != null ? Math.max(0f, arousalNorm - ENERGY_BAND) : null,
            arousalNorm != null ? Math.min(1f, arousalNorm + ENERGY_BAND) : null,
            null,
            null,
            null,
            Math.max(limit, 20));
    var candidates = candidateRetrievalService.searchLibrary(filters);
    var ids = candidates.stream().map(CandidateRetrievalService.TrackCandidate::id).toList();
    List<UUID> result = new ArrayList<>(ids);
    Collections.shuffle(result);
    return result;
  }

  private List<UUID> findNeverPlayedWithFeatures(
      UUID userId, ListeningSessionEntity session, int limit) {
    Float energyNorm = normalizeEnergy(session.getEnergy());

    var filters =
        new CandidateRetrievalService.NeverPlayedFilters(
            energyNorm != null ? Math.max(0f, energyNorm - ENERGY_BAND) : null,
            energyNorm != null ? Math.min(1f, energyNorm + ENERGY_BAND) : null,
            null,
            null,
            energyNorm != null && energyNorm > 0.6f ? 0.4f : null,
            null,
            Math.max(limit, 20));
    var candidates = candidateRetrievalService.findNeverPlayedTracks(userId, filters);
    var ids = candidates.stream().map(CandidateRetrievalService.TrackCandidate::id).toList();
    List<UUID> result = new ArrayList<>(ids);
    Collections.shuffle(result);
    return result;
  }

  private List<UUID> filterBySessionFeatures(List<UUID> trackIds, ListeningSessionEntity session) {
    Float energyNorm = normalizeEnergy(session.getEnergy());
    if (energyNorm == null || trackIds.isEmpty()) return trackIds;

    var details = candidateRetrievalService.getTrackDetails(trackIds);
    return details.stream()
        .filter(
            t ->
                t.energy() == null
                    || (t.energy() >= energyNorm - POST_FILTER_ENERGY_BAND
                        && t.energy() <= energyNorm + POST_FILTER_ENERGY_BAND))
        .map(CandidateRetrievalService.TrackCandidate::id)
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private Float normalizeEnergy(Float sessionValue) {
    if (sessionValue == null) return null;
    return Math.max(0f, Math.min(1f, sessionValue / 10f));
  }

  private boolean hasDiscoveryTag(ListeningSessionEntity session) {
    String[] tags = session.getMoodTags();
    return tags != null && Arrays.asList(tags).contains("discovery");
  }

  private List<UUID> findRandomTracks(
      Set<UUID> existingTrackIds, Set<UUID> excluded, int sampleSize) {
    var candidateIds = itemRepository.findRandomAudioTrackIds(Math.max(sampleSize, 20));
    return candidateIds.stream()
        .filter(id -> !existingTrackIds.contains(id) && !excluded.contains(id))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private List<UUID> findRandomFavorites(
      UUID userId, Set<UUID> existingTrackIds, Set<UUID> excluded) {
    var candidateIds =
        playStateRepository.findAllByUserIdAndIsFavoriteTrue(userId).stream()
            .map(ps -> ps.getItem().getId())
            .filter(id -> !existingTrackIds.contains(id) && !excluded.contains(id))
            .collect(Collectors.toCollection(ArrayList::new));
    Collections.shuffle(candidateIds);
    return candidateIds;
  }

  private void addUniqueUpTo(
      List<UUID> target,
      List<UUID> source,
      int maxToAdd,
      Set<UUID> existingTrackIds,
      Set<UUID> excluded) {
    int added = 0;
    for (UUID id : source) {
      if (added >= maxToAdd) break;
      if (!target.contains(id) && !existingTrackIds.contains(id) && !excluded.contains(id)) {
        target.add(id);
        added++;
      }
    }
  }
}
