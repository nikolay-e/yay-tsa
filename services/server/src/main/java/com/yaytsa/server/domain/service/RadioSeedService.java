package com.yaytsa.server.domain.service;

import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.dto.response.RadioSeedsResponse.RadioSeed;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity;
import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity.RadioSeedCacheId;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.RadioSeedCacheRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RadioSeedService {

  private static final Logger log = LoggerFactory.getLogger(RadioSeedService.class);
  private static final int MIN_USER_TRACKS_FOR_SEEDS = 5;
  private static final int OVERFETCH_SEEDS = 20;
  private static final int MAX_SEEDS = 10;
  private static final int MAX_PER_ARTIST = 2;
  private static final Duration CACHE_TTL = Duration.ofHours(12);

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final ItemRepository itemRepository;
  private final RadioSeedClient radioSeedClient;
  private final RadioSeedCacheRepository seedCacheRepository;

  public RadioSeedService(
      TrackFeaturesRepository trackFeaturesRepository,
      ItemRepository itemRepository,
      RadioSeedClient radioSeedClient,
      RadioSeedCacheRepository seedCacheRepository) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.itemRepository = itemRepository;
    this.radioSeedClient = radioSeedClient;
    this.seedCacheRepository = seedCacheRepository;
  }

  public RadioSeedsResponse getSeeds(UUID userId) {
    java.time.Instant computedAt = seedCacheRepository.findComputedAtByUserId(userId);
    if (computedAt != null && !isStale(computedAt)) {
      List<UUID> cachedTrackIds = seedCacheRepository.findTrackIdsByUserId(userId);
      if (!cachedTrackIds.isEmpty()) {
        log.debug("Returning {} cached radio seeds for user {}", cachedTrackIds.size(), userId);
        return enrichSeeds(cachedTrackIds);
      }
    }
    return recomputeAndCache(userId);
  }

  @Transactional
  public void invalidateCache() {
    seedCacheRepository.deleteAllCache();
    log.info("Radio seeds cache invalidated (all users)");
  }

  @Transactional
  public void invalidateCacheForUser(UUID userId) {
    seedCacheRepository.deleteByUserId(userId);
    log.info("Radio seeds cache invalidated for user {}", userId);
  }

  @Transactional
  public RadioSeedsResponse recomputeAndCache(UUID userId) {
    List<UUID> seedTrackIds = computeSeedTrackIds(userId);
    seedCacheRepository.deleteByUserId(userId);

    if (seedTrackIds.isEmpty()) {
      return new RadioSeedsResponse(List.of());
    }

    OffsetDateTime now = OffsetDateTime.now();
    List<RadioSeedCacheEntity> entities = new ArrayList<>();
    for (int i = 0; i < seedTrackIds.size(); i++) {
      var entity = new RadioSeedCacheEntity();
      entity.setId(new RadioSeedCacheId(userId, (short) i));
      entity.setTrackId(seedTrackIds.get(i));
      entity.setComputedAt(now);
      entities.add(entity);
    }
    seedCacheRepository.saveAll(entities);
    log.info("Cached {} radio seeds for user {}", seedTrackIds.size(), userId);

    return enrichSeeds(seedTrackIds);
  }

  private List<UUID> computeSeedTrackIds(UUID userId) {
    var userTracks = trackFeaturesRepository.findMertEmbeddingsForUserWithPositiveAffinity(userId);

    List<RadioSeedClient.SeedTrackInput> trackInputs = new ArrayList<>();
    for (var row : userTracks) {
      UUID trackId = (UUID) row[0];
      float[] embedding = parseEmbedding(row[1]);
      double affinity = ((Number) row[2]).doubleValue();
      if (embedding == null) continue;

      trackInputs.add(
          new RadioSeedClient.SeedTrackInput(
              trackId.toString(), floatArrayToList(embedding), affinity));
    }

    if (trackInputs.size() >= MIN_USER_TRACKS_FOR_SEEDS) {
      List<RadioSeedClient.SeedResult> seeds =
          radioSeedClient.computeSeeds(trackInputs, OVERFETCH_SEEDS);
      if (!seeds.isEmpty()) {
        return seeds.stream().map(s -> UUID.fromString(s.trackId())).toList();
      }
      log.warn(
          "ML seed service returned empty for user {} with {} embedded tracks, falling back to"
              + " affinity",
          userId,
          trackInputs.size());
    }

    List<UUID> affinityTrackIds =
        trackFeaturesRepository.findTopAffinityTrackIds(userId, OVERFETCH_SEEDS);
    if (!affinityTrackIds.isEmpty()) {
      log.info(
          "Using top affinity tracks as radio seeds for user {} ({} tracks)",
          userId,
          affinityTrackIds.size());
      return affinityTrackIds;
    }

    var fallbackInputs = buildFallbackInputs();
    if (fallbackInputs.size() < MIN_USER_TRACKS_FOR_SEEDS) {
      log.info(
          "Not enough embedded tracks for radio seeds: {} (need {})",
          fallbackInputs.size(),
          MIN_USER_TRACKS_FOR_SEEDS);
      return List.of();
    }

    List<RadioSeedClient.SeedResult> seeds =
        radioSeedClient.computeSeeds(fallbackInputs, OVERFETCH_SEEDS);
    if (seeds.isEmpty()) {
      return List.of();
    }

    return seeds.stream().map(s -> UUID.fromString(s.trackId())).toList();
  }

  private List<RadioSeedClient.SeedTrackInput> buildFallbackInputs() {
    var allEmbedded = trackFeaturesRepository.findAllWithMertEmbedding();
    List<RadioSeedClient.SeedTrackInput> inputs = new ArrayList<>();
    for (var row : allEmbedded) {
      UUID trackId = (UUID) row[0];
      float[] embedding = parseEmbedding(row[1]);
      if (embedding == null) continue;
      inputs.add(
          new RadioSeedClient.SeedTrackInput(trackId.toString(), floatArrayToList(embedding), 1.0));
    }
    return inputs;
  }

  private RadioSeedsResponse enrichSeeds(List<UUID> seedTrackIds) {
    Map<UUID, ItemEntity> itemsById =
        itemRepository.findAllById(seedTrackIds).stream()
            .collect(Collectors.toMap(ItemEntity::getId, i -> i));

    Map<String, Integer> artistCount = new HashMap<>();
    Set<UUID> albumsSeen = new HashSet<>();
    List<RadioSeed> enriched = new ArrayList<>();

    for (UUID trackId : seedTrackIds) {
      if (enriched.size() >= MAX_SEEDS) break;

      ItemEntity track = itemsById.get(trackId);
      if (track == null) continue;

      ItemEntity album = track.getParent();
      UUID albumId = album != null ? album.getId() : null;

      if (albumId != null && !albumsSeen.add(albumId)) continue;

      String artistName = null;
      if (album != null && album.getParent() != null) {
        artistName = album.getParent().getName();
      }

      if (artistName != null) {
        int count = artistCount.getOrDefault(artistName, 0);
        if (count >= MAX_PER_ARTIST) continue;
        artistCount.put(artistName, count + 1);
      }

      String albumName = album != null ? album.getName() : null;
      String imageTag = null;
      if (album != null) {
        imageTag =
            album.getImages().stream()
                .filter(img -> Boolean.TRUE.equals(img.getIsPrimary()))
                .findFirst()
                .map(ImageEntity::getTag)
                .orElse(null);
      }

      enriched.add(
          new RadioSeed(trackId, track.getName(), artistName, albumName, albumId, imageTag));
    }

    return new RadioSeedsResponse(enriched);
  }

  private boolean isStale(java.time.Instant computedAt) {
    return computedAt.plus(CACHE_TTL).isBefore(java.time.Instant.now());
  }

  private static float[] parseEmbedding(Object raw) {
    if (raw instanceof float[] arr) return arr;
    if (raw == null) return null;
    try {
      String str = raw.toString();
      if (str.startsWith("[")) str = str.substring(1);
      if (str.endsWith("]")) str = str.substring(0, str.length() - 1);
      String[] parts = str.split(",");
      float[] result = new float[parts.length];
      for (int i = 0; i < parts.length; i++) {
        result[i] = Float.parseFloat(parts[i].trim());
      }
      return result;
    } catch (NumberFormatException e) {
      log.warn("Malformed embedding data: {}", e.getMessage());
      return null;
    }
  }

  private static List<Float> floatArrayToList(float[] arr) {
    return IntStream.range(0, arr.length).mapToObj(i -> arr[i]).toList();
  }
}
