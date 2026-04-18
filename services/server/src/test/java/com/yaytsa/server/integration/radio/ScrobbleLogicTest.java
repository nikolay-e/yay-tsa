package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.SessionService;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import com.yaytsa.server.integration.Feature;
import com.yaytsa.server.integration.MockExternalServicesConfig;
import com.yaytsa.server.integration.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfig.class, MockExternalServicesConfig.class})
@ActiveProfiles("tc")
@DisplayName("Feature: Scrobble logic (FEAT-SCROBBLE)")
class ScrobbleLogicTest {

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
  @Feature(id = "FEAT-SCROBBLE", ac = "AC-01")
  @DisplayName("AC-01: Track played over 50% is scrobbled")
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
  @Feature(id = "FEAT-SCROBBLE", ac = "AC-02")
  @DisplayName("AC-02: Track played over 240s is scrobbled regardless of percentage")
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
  @Feature(id = "FEAT-SCROBBLE", ac = "AC-03")
  @DisplayName("AC-03: Short track under 30s is never scrobbled")
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
  @Feature(id = "FEAT-SCROBBLE", ac = "AC-04")
  @DisplayName("AC-04: Track played to 95% is marked completed")
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
  @Feature(id = "FEAT-SCROBBLE", ac = "AC-05")
  @DisplayName("AC-05: Track played under 50% is marked skipped")
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
