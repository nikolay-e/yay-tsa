package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.RadioSeedService;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.RadioSeedCacheRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@Import({TestcontainersConfig.class, RadioSeedServiceTest.MockClientConfig.class})
@ActiveProfiles("tc")
@DisplayName("RadioSeedService")
class RadioSeedServiceTest {

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
  @Autowired private RadioSeedCacheRepository seedCacheRepository;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          seedCacheRepository.deleteAllCache();

          UserEntity user = new UserEntity();
          user.setUsername("svctest_" + UUID.randomUUID().toString().substring(0, 8));
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
  @DisplayName("enriches cached seeds with track/album/artist/image metadata")
  void enrichesSeedsWithMetadata() {
    UUID trackId = createArtistAlbumTrack("Artist A", "Album A", "Track A", "img123");
    populateCache(userId, List.of(trackId));

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);

    assertThat(response.seeds()).hasSize(1);
    var seed = response.seeds().get(0);
    assertThat(seed.trackId()).isEqualTo(trackId);
    assertThat(seed.name()).isEqualTo("Track A");
    assertThat(seed.artistName()).isEqualTo("Artist A");
    assertThat(seed.albumName()).isEqualTo("Album A");
    assertThat(seed.imageTag()).isEqualTo("img123");
    assertThat(seed.albumId()).isNotNull();
  }

  @Test
  @DisplayName("deduplicates by album — one seed per album")
  void deduplicatesByAlbum() {
    List<UUID> trackIds = new ArrayList<>();
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = persistArtist("Dedup Artist");
          ItemEntity album = persistAlbum("Same Album", artist, null);
          for (int i = 0; i < 4; i++) {
            trackIds.add(persistTrack("Track " + i, album).getId());
          }
          em.flush();
          em.clear();
        });

    populateCache(userId, trackIds);

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);

    assertThat(response.seeds()).hasSize(1);
  }

  @Test
  @DisplayName("limits seeds per artist to 2")
  void capsPerArtist() {
    List<UUID> trackIds = new ArrayList<>();
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          ItemEntity artist = persistArtist("Prolific Artist");
          for (int i = 0; i < 5; i++) {
            ItemEntity album = persistAlbum("Album " + i, artist, null);
            trackIds.add(persistTrack("PTrack " + i, album).getId());
          }
          em.flush();
          em.clear();
        });

    populateCache(userId, trackIds);

    RadioSeedsResponse response = radioSeedService.getSeeds(userId);

    long count =
        response.seeds().stream().filter(s -> "Prolific Artist".equals(s.artistName())).count();
    assertThat(count).isLessThanOrEqualTo(2);
  }

  @Test
  @DisplayName("returns empty for unknown user")
  void emptyForUnknownUser() {
    RadioSeedsResponse response = radioSeedService.getSeeds(UUID.randomUUID());
    assertThat(response.seeds()).isEmpty();
  }

  @Test
  @DisplayName("cache invalidation clears seeds")
  void cacheInvalidationWorks() {
    UUID trackId = createArtistAlbumTrack("Inv Artist", "Inv Album", "Inv Track", null);
    populateCache(userId, List.of(trackId));

    assertThat(seedCacheRepository.findTrackIdsByUserId(userId)).isNotEmpty();

    radioSeedService.invalidateCacheForUser(userId);
    assertThat(seedCacheRepository.findTrackIdsByUserId(userId)).isEmpty();

    populateCache(userId, List.of(trackId));
    radioSeedService.invalidateCache();
    assertThat(seedCacheRepository.findTrackIdsByUserId(userId)).isEmpty();
  }

  private UUID createArtistAlbumTrack(
      String artistName, String albumName, String trackName, String imageTag) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    return tx.execute(
        status -> {
          ItemEntity artist = persistArtist(artistName);
          ItemEntity album = persistAlbum(albumName, artist, imageTag);
          ItemEntity track = persistTrack(trackName, album);
          em.flush();
          em.clear();
          return track.getId();
        });
  }

  private ItemEntity persistArtist(String name) {
    ItemEntity artist = new ItemEntity();
    artist.setType(ItemType.MusicArtist);
    artist.setName(name);
    artist.setPath("artist:" + UUID.randomUUID());
    em.persist(artist);
    return artist;
  }

  private ItemEntity persistAlbum(String name, ItemEntity artist, String imageTag) {
    ItemEntity album = new ItemEntity();
    album.setType(ItemType.MusicAlbum);
    album.setName(name);
    album.setPath("album:" + UUID.randomUUID());
    album.setParent(artist);
    em.persist(album);

    if (imageTag != null) {
      ImageEntity img = new ImageEntity();
      img.setItem(album);
      img.setType(ImageType.Primary);
      img.setPath("/images/" + UUID.randomUUID() + ".jpg");
      img.setTag(imageTag);
      img.setIsPrimary(true);
      em.persist(img);
    }

    return album;
  }

  private ItemEntity persistTrack(String name, ItemEntity album) {
    ItemEntity track = new ItemEntity();
    track.setType(ItemType.AudioTrack);
    track.setName(name);
    track.setPath("audio:" + UUID.randomUUID());
    track.setParent(album);
    em.persist(track);
    return track;
  }

  private void populateCache(UUID uid, List<UUID> tracks) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          OffsetDateTime now = OffsetDateTime.now();
          for (int i = 0; i < tracks.size(); i++) {
            var entity = new RadioSeedCacheEntity();
            entity.setId(new RadioSeedCacheEntity.RadioSeedCacheId(uid, (short) i));
            entity.setTrackId(tracks.get(i));
            entity.setComputedAt(now);
            em.persist(entity);
          }
          em.flush();
          em.clear();
        });
  }
}
