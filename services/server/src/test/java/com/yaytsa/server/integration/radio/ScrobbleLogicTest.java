package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.SessionService;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfig.class, ScrobbleLogicTest.MockConfig.class})
@ActiveProfiles("tc")
@Tag("testcontainers")
@DisplayName("Scrobble Logic")
class ScrobbleLogicTest {

  @org.springframework.boot.test.context.TestConfiguration
  static class MockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    com.yaytsa.server.infrastructure.client.RadioSeedClient testRadioSeedClient() {
      return new com.yaytsa.server.infrastructure.client.RadioSeedClient(
          org.springframework.web.client.RestClient.builder(), "http://localhost:19999");
    }
  }

  @Autowired private SessionService sessionService;
  @Autowired private PlayHistoryRepository playHistoryRepository;
  @Autowired private PlayStateRepository playStateRepository;
  @Autowired private AudioTrackRepository audioTrackRepository;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;

  private UUID userId;
  private final String deviceId = "scrobble-test-device";

  @BeforeEach
  void setUp() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          UserEntity user = new UserEntity();
          user.setUsername("scrobble_" + UUID.randomUUID().toString().substring(0, 8));
          user.setPasswordHash("$2a$10$dummyhash");
          user.setActive(true);
          user.setAdmin(false);
          em.persist(user);
          userId = user.getId();
          em.flush();
          em.clear();
        });
  }

  private UUID createTrack(long durationMs) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    return tx.execute(
        status -> {
          ItemEntity artist = new ItemEntity();
          artist.setType(ItemType.MusicArtist);
          artist.setName("Scrobble Artist");
          artist.setPath("artist:scr:" + UUID.randomUUID());
          em.persist(artist);

          ItemEntity album = new ItemEntity();
          album.setType(ItemType.MusicAlbum);
          album.setName("Scrobble Album");
          album.setPath("album:scr:" + UUID.randomUUID());
          album.setParent(artist);
          em.persist(album);

          ItemEntity trackItem = new ItemEntity();
          trackItem.setType(ItemType.AudioTrack);
          trackItem.setName("Track " + UUID.randomUUID().toString().substring(0, 4));
          trackItem.setPath("audio:scr:" + UUID.randomUUID());
          trackItem.setParent(album);
          em.persist(trackItem);

          AudioTrackEntity audioTrack = new AudioTrackEntity();
          audioTrack.setItem(trackItem);
          audioTrack.setDurationMs(durationMs);
          audioTrack.setAlbum(album);
          audioTrack.setAlbumArtist(artist);
          em.persist(audioTrack);

          em.flush();
          return trackItem.getId();
        });
  }

  private void playAndStop(UUID trackId, long positionMs) {
    sessionService.reportPlaybackStart(userId, deviceId, "TestDevice", trackId);
    sessionService.reportPlaybackStopped(userId, deviceId, trackId, positionMs);
  }

  @Test
  @DisplayName(">50% of 300s track → scrobbled, play_count=1")
  void scrobbleOverHalf() {
    UUID trackId = createTrack(300_000L);
    playAndStop(trackId, 160_000L);

    var history =
        playHistoryRepository.findAll().stream()
            .filter(h -> h.getItem().getId().equals(trackId))
            .findFirst()
            .orElseThrow();

    assertThat(history.getScrobbled()).isTrue();
    assertThat(history.getCompleted()).isFalse();
    assertThat(history.getSkipped()).isFalse();

    var playState = playStateRepository.findByUserIdAndItemId(userId, trackId);
    assertThat(playState).isPresent();
    assertThat(playState.get().getPlayCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(">240s of 600s track → scrobbled (threshold cap)")
  void scrobbleOver240Seconds() {
    UUID trackId = createTrack(600_000L);
    playAndStop(trackId, 250_000L);

    var history =
        playHistoryRepository.findAll().stream()
            .filter(h -> h.getItem().getId().equals(trackId))
            .findFirst()
            .orElseThrow();

    assertThat(history.getScrobbled()).isTrue();
  }

  @Test
  @DisplayName("Short track <30s → never scrobbled")
  void shortTrackNoScrobble() {
    UUID trackId = createTrack(20_000L);
    playAndStop(trackId, 15_000L);

    var history =
        playHistoryRepository.findAll().stream()
            .filter(h -> h.getItem().getId().equals(trackId))
            .toList();

    if (!history.isEmpty()) {
      assertThat(history.get(0).getScrobbled()).isFalse();
    }

    var playState = playStateRepository.findByUserIdAndItemId(userId, trackId);
    assertThat(playState).isEmpty();
  }

  @Test
  @DisplayName("95% of 200s track → completed")
  void completedTrack() {
    UUID trackId = createTrack(200_000L);
    playAndStop(trackId, 195_000L);

    var history =
        playHistoryRepository.findAll().stream()
            .filter(h -> h.getItem().getId().equals(trackId))
            .findFirst()
            .orElseThrow();

    assertThat(history.getCompleted()).isTrue();
    assertThat(history.getScrobbled()).isTrue();
  }

  @Test
  @DisplayName("<50% of 300s track → skipped")
  void skippedTrack() {
    UUID trackId = createTrack(300_000L);
    playAndStop(trackId, 30_000L);

    var history =
        playHistoryRepository.findAll().stream()
            .filter(h -> h.getItem().getId().equals(trackId))
            .findFirst()
            .orElseThrow();

    assertThat(history.getSkipped()).isTrue();
    assertThat(history.getScrobbled()).isFalse();
  }
}
