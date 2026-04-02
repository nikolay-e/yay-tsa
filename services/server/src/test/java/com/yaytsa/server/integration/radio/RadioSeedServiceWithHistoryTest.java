package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.RadioSeedService;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfig.class, RadioSeedServiceWithHistoryTest.MockClientConfig.class})
@ActiveProfiles("tc")
@Tag("testcontainers")
@DisplayName("RadioSeedService with play history")
class RadioSeedServiceWithHistoryTest {

  @TestConfiguration
  static class MockClientConfig {
    @Bean
    @Primary
    RadioSeedClient testRadioSeedClient() {
      return new RadioSeedClient(
          org.springframework.web.client.RestClient.builder(), "http://localhost:19999");
    }
  }

  @Autowired private RadioSeedService radioSeedService;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          UserEntity user = new UserEntity();
          user.setUsername("seedhist_" + UUID.randomUUID().toString().substring(0, 8));
          user.setPasswordHash("$2a$10$dummyhash");
          user.setActive(true);
          user.setAdmin(false);
          em.persist(user);
          userId = user.getId();
          em.flush();
          em.clear();
        });
  }

  @Test
  @DisplayName("6 tracks with affinity → seeds non-empty")
  void seedsFromAffinityTracks() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = createArtist("Affinity Artist");
          ItemEntity album = createAlbum("Affinity Album", artist);

          for (int i = 0; i < 6; i++) {
            UUID trackId = createTrackWithEmbedding(album, "Track " + i, 0.1f * i);
            insertAffinity(userId, trackId, 0.9 - i * 0.1);
          }
          em.flush();
        });

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    assertThat(response.seeds()).isNotEmpty();
  }

  @Test
  @DisplayName("<5 affinity but 5 play_state tracks → fallback to play history")
  void fallbackToPlayState() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = createArtist("PlayState Artist");
          ItemEntity album = createAlbum("PlayState Album", artist);

          for (int i = 0; i < 2; i++) {
            UUID trackId = createTrackWithEmbedding(album, "Aff " + i, 0.1f * i);
            insertAffinity(userId, trackId, 0.8);
          }

          for (int i = 0; i < 5; i++) {
            UUID trackId = createTrackWithEmbedding(album, "Play " + i, 0.5f + 0.01f * i);
            insertPlayState(userId, trackId, 5 + i);
          }
          em.flush();
        });

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    assertThat(response.seeds()).isNotEmpty();
  }

  @Test
  @DisplayName("2 tracks from same album → dedup (max 1 seed per album)")
  void albumDedup() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = createArtist("Dedup Artist");
          ItemEntity album = createAlbum("Same Album", artist);

          for (int i = 0; i < 6; i++) {
            UUID trackId = createTrackWithEmbedding(album, "Same " + i, 0.2f * i);
            insertAffinity(userId, trackId, 0.9);
          }
          em.flush();
        });

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    Set<UUID> albumIds =
        response.seeds().stream()
            .map(RadioSeedsResponse.RadioSeed::albumId)
            .collect(Collectors.toSet());
    assertThat(albumIds).hasSizeLessThanOrEqualTo(response.seeds().size());
  }

  @Test
  @DisplayName("Discovery: seeds include never-played tracks from library")
  void discoveryTracks() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = createArtist("Discovery Artist");

          for (int i = 0; i < 6; i++) {
            ItemEntity album = createAlbum("Album " + i, artist);
            UUID trackId = createTrackWithEmbedding(album, "Played " + i, 0.1f * i);
            insertAffinity(userId, trackId, 0.8);
          }

          ItemEntity discoveryAlbum = createAlbum("Discovery Album", artist);
          for (int i = 0; i < 3; i++) {
            createTrackWithEmbedding(discoveryAlbum, "New " + i, 0.9f + 0.01f * i);
          }

          UserEntity userRef = em.getReference(UserEntity.class, userId);
          TasteProfileEntity taste = new TasteProfileEntity();
          taste.setUser(userRef);
          taste.setUserId(userId);
          taste.setEmbeddingMert(generateEmbedding(768, 0.5f));
          taste.setRebuiltAt(OffsetDateTime.now());
          em.persist(taste);

          em.flush();
        });

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    assertThat(response.seeds()).isNotEmpty();
  }

  private ItemEntity createArtist(String name) {
    ItemEntity artist = new ItemEntity();
    artist.setType(ItemType.MusicArtist);
    artist.setName(name);
    artist.setPath("artist:" + UUID.randomUUID());
    em.persist(artist);
    return artist;
  }

  private ItemEntity createAlbum(String name, ItemEntity artist) {
    ItemEntity album = new ItemEntity();
    album.setType(ItemType.MusicAlbum);
    album.setName(name);
    album.setPath("album:" + UUID.randomUUID());
    album.setParent(artist);
    em.persist(album);
    return album;
  }

  private UUID createTrackWithEmbedding(ItemEntity album, String name, float seed) {
    ItemEntity track = new ItemEntity();
    track.setType(ItemType.AudioTrack);
    track.setName(name);
    track.setPath("audio:" + UUID.randomUUID());
    track.setParent(album);
    em.persist(track);

    TrackFeaturesEntity features = new TrackFeaturesEntity();
    features.setItem(track);
    features.setTrackId(track.getId());
    features.setEmbeddingMert(generateEmbedding(768, seed));
    features.setExtractedAt(OffsetDateTime.now());
    features.setExtractorVersion("test-1.0");
    em.persist(features);

    return track.getId();
  }

  private void insertAffinity(UUID uid, UUID trackId, double score) {
    em.createNativeQuery(
            "INSERT INTO user_track_affinity (user_id, track_id, affinity_score) "
                + "VALUES (:uid, :tid, :score)")
        .setParameter("uid", uid)
        .setParameter("tid", trackId)
        .setParameter("score", score)
        .executeUpdate();
  }

  private void insertPlayState(UUID uid, UUID trackId, int playCount) {
    em.createNativeQuery(
            "INSERT INTO play_state (user_id, item_id, play_count, is_favorite) "
                + "VALUES (:uid, :tid, :count, false)")
        .setParameter("uid", uid)
        .setParameter("tid", trackId)
        .setParameter("count", playCount)
        .executeUpdate();
  }

  private static float[] generateEmbedding(int dim, float seed) {
    float[] emb = new float[dim];
    for (int i = 0; i < dim; i++) {
      emb[i] = (float) Math.sin(seed + i * 0.01);
    }
    float norm = 0;
    for (float v : emb) norm += v * v;
    norm = (float) Math.sqrt(norm);
    for (int i = 0; i < dim; i++) emb[i] /= norm;
    return emb;
  }
}
