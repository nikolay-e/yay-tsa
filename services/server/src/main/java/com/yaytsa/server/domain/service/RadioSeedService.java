package com.yaytsa.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.dto.response.RadioSeedsResponse.RadioSeed;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.Duration;
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

@Service
public class RadioSeedService {

  private static final Logger log = LoggerFactory.getLogger(RadioSeedService.class);
  private static final int MIN_USER_TRACKS_FOR_SEEDS = 20;
  private static final int OVERFETCH_SEEDS = 20;
  private static final int MAX_SEEDS = 10;
  private static final int MAX_PER_ARTIST = 2;

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final ItemRepository itemRepository;
  private final RadioSeedClient radioSeedClient;

  private final Cache<UUID, RadioSeedsResponse> seedsCache =
      Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(12)).build();

  public RadioSeedService(
      TrackFeaturesRepository trackFeaturesRepository,
      ItemRepository itemRepository,
      RadioSeedClient radioSeedClient) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.itemRepository = itemRepository;
    this.radioSeedClient = radioSeedClient;
  }

  public RadioSeedsResponse getSeeds(UUID userId) {
    return seedsCache.get(userId, this::computeSeeds);
  }

  public void invalidateCache() {
    seedsCache.invalidateAll();
  }

  private RadioSeedsResponse computeSeeds(UUID userId) {
    var userTracks = trackFeaturesRepository.findMertEmbeddingsForUserWithPositiveAffinity(userId);

    List<RadioSeedClient.SeedTrackInput> trackInputs = new ArrayList<>();
    for (var row : userTracks) {
      UUID trackId = (UUID) row[0];
      float[] embedding = (float[]) row[1];
      double affinity = ((Number) row[2]).doubleValue();

      trackInputs.add(
          new RadioSeedClient.SeedTrackInput(
              trackId.toString(), floatArrayToList(embedding), affinity));
    }

    if (trackInputs.size() < MIN_USER_TRACKS_FOR_SEEDS) {
      log.info(
          "Not enough user-interacted tracks for radio seeds: {} (need {})",
          trackInputs.size(),
          MIN_USER_TRACKS_FOR_SEEDS);
      return new RadioSeedsResponse(List.of());
    }

    List<RadioSeedClient.SeedResult> seeds =
        radioSeedClient.computeSeeds(trackInputs, OVERFETCH_SEEDS);
    if (seeds.isEmpty()) {
      return new RadioSeedsResponse(List.of());
    }

    List<UUID> seedTrackIds = seeds.stream().map(s -> UUID.fromString(s.trackId())).toList();

    return enrichSeeds(seedTrackIds);
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

  private static List<Float> floatArrayToList(float[] arr) {
    return IntStream.range(0, arr.length).mapToObj(i -> arr[i]).toList();
  }
}
