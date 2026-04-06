package com.yaytsa.server.domain.service;

import com.yaytsa.server.domain.util.EmbeddingUtils;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.dto.response.RadioSeedsResponse.RadioSeed;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class RadioSeedService {

  private static final int MIN_USER_TRACKS_FOR_SEEDS = 5;
  private static final int SEED_CANDIDATES = 100;
  private static final double TEMPERATURE = 0.7;
  private static final double DISCOVERY_AFFINITY = 0.3;
  private static final int DISCOVERY_LIMIT = 80;
  private static final UUID EMPTY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final ItemRepository itemRepository;
  private final RadioSeedClient radioSeedClient;
  private final TasteProfileService tasteProfileService;

  public RadioSeedService(
      TrackFeaturesRepository trackFeaturesRepository,
      ItemRepository itemRepository,
      RadioSeedClient radioSeedClient,
      TasteProfileService tasteProfileService) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.itemRepository = itemRepository;
    this.radioSeedClient = radioSeedClient;
    this.tasteProfileService = tasteProfileService;
  }

  @Transactional(readOnly = true)
  public RadioSeedsResponse getSeeds(UUID userId) {
    List<UUID> seedTrackIds = computeSeedTrackIds(userId);
    return enrichSeeds(seedTrackIds);
  }

  private List<UUID> computeSeedTrackIds(UUID userId) {
    List<RadioSeedClient.SeedTrackInput> trackInputs = buildSeedInputs(userId);

    if (trackInputs.size() >= MIN_USER_TRACKS_FOR_SEEDS) {
      List<RadioSeedClient.SeedResult> seeds =
          radioSeedClient.computeSeeds(trackInputs, SEED_CANDIDATES, TEMPERATURE);
      if (!seeds.isEmpty()) {
        return seeds.stream().map(s -> UUID.fromString(s.trackId())).toList();
      }
      log.warn(
          "ML seed service returned empty for user {} with {} tracks, using direct list",
          userId,
          trackInputs.size());
    }

    if (!trackInputs.isEmpty()) {
      return trackInputs.stream().map(t -> UUID.fromString(t.trackId())).toList();
    }

    log.info("No play history with embeddings for user {}, returning empty seeds", userId);
    return List.of();
  }

  private List<RadioSeedClient.SeedTrackInput> buildSeedInputs(UUID userId) {
    var affinityTracks =
        trackFeaturesRepository.findMertEmbeddingsForUserWithPositiveAffinity(userId);
    List<RadioSeedClient.SeedTrackInput> trackInputs = new ArrayList<>();
    Set<UUID> seenTrackIds = new HashSet<>();
    for (var row : affinityTracks) {
      UUID trackId = (UUID) row[0];
      float[] embedding = EmbeddingUtils.parse(row[1]);
      double affinity = ((Number) row[2]).doubleValue();
      if (embedding == null) continue;
      seenTrackIds.add(trackId);
      trackInputs.add(
          new RadioSeedClient.SeedTrackInput(
              trackId.toString(), floatArrayToList(embedding), affinity));
    }

    var playHistoryTracks =
        trackFeaturesRepository.findMertEmbeddingsForUserPlayHistory(userId, 200);
    for (var row : playHistoryTracks) {
      UUID trackId = (UUID) row[0];
      if (seenTrackIds.contains(trackId)) continue;
      float[] embedding = EmbeddingUtils.parse(row[1]);
      double affinity = ((Number) row[2]).doubleValue();
      if (embedding == null) continue;
      seenTrackIds.add(trackId);
      trackInputs.add(
          new RadioSeedClient.SeedTrackInput(
              trackId.toString(), floatArrayToList(embedding), affinity));
    }

    addDiscoveryTracks(userId, seenTrackIds, trackInputs);

    if (!trackInputs.isEmpty()) {
      log.info(
          "Built {} seed input tracks for user {} ({} user + {} discovery)",
          trackInputs.size(),
          userId,
          seenTrackIds.size() - countDiscovery(trackInputs),
          countDiscovery(trackInputs));
    }
    return trackInputs;
  }

  private void addDiscoveryTracks(
      UUID userId, Set<UUID> seenTrackIds, List<RadioSeedClient.SeedTrackInput> trackInputs) {
    TasteProfileEntity profile = tasteProfileService.getProfile(userId);
    if (profile == null || profile.getEmbeddingMert() == null) {
      log.debug("No taste profile or MERT embedding for user {}, skipping discovery", userId);
      return;
    }

    String userEmbedding = EmbeddingUtils.format(profile.getEmbeddingMert());
    List<UUID> excludeIds =
        seenTrackIds.isEmpty() ? List.of(EMPTY_UUID) : new ArrayList<>(seenTrackIds);

    List<Object[]> discoveryRows =
        trackFeaturesRepository.findDiscoveryEmbeddingsByUserProfile(
            userEmbedding, excludeIds, DISCOVERY_LIMIT);

    int added = 0;
    for (var row : discoveryRows) {
      UUID trackId = (UUID) row[0];
      if (seenTrackIds.contains(trackId)) continue;
      float[] embedding = EmbeddingUtils.parse(row[1]);
      if (embedding == null) continue;
      seenTrackIds.add(trackId);
      trackInputs.add(
          new RadioSeedClient.SeedTrackInput(
              trackId.toString(), floatArrayToList(embedding), DISCOVERY_AFFINITY));
      added++;
    }
    if (added > 0) {
      log.info("Added {} discovery tracks from library for user {}", added, userId);
    }
  }

  private RadioSeedsResponse enrichSeeds(List<UUID> seedTrackIds) {
    if (seedTrackIds.isEmpty()) {
      return new RadioSeedsResponse(List.of());
    }

    Map<UUID, ItemEntity> itemsById =
        itemRepository.findAllById(seedTrackIds).stream()
            .collect(Collectors.toMap(ItemEntity::getId, i -> i));

    Set<UUID> albumsSeen = new HashSet<>();
    List<RadioSeed> enriched = new ArrayList<>();

    for (UUID trackId : seedTrackIds) {
      ItemEntity track = itemsById.get(trackId);
      if (track == null) continue;

      ItemEntity album = track.getParent();
      UUID albumId = album != null ? album.getId() : null;

      if (albumId != null && !albumsSeen.add(albumId)) continue;

      String artistName = null;
      if (album != null && album.getParent() != null) {
        artistName = album.getParent().getName();
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

  private static int countDiscovery(List<RadioSeedClient.SeedTrackInput> inputs) {
    return (int) inputs.stream().filter(t -> t.affinityScore() == DISCOVERY_AFFINITY).count();
  }

  private static List<Float> floatArrayToList(float[] arr) {
    return IntStream.range(0, arr.length).mapToObj(i -> arr[i]).toList();
  }
}
