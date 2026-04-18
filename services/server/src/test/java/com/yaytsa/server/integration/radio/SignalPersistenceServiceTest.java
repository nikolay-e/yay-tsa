package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.domain.service.SignalPersistenceService;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.integration.Feature;
import com.yaytsa.server.integration.MockExternalServicesConfig;
import com.yaytsa.server.integration.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.util.Map;
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
@DisplayName("Feature: Signal persistence (FEAT-SIGNALS)")
class SignalPersistenceServiceTest {

  @Autowired private SignalPersistenceService signalService;
  @Autowired private ListeningSessionService sessionService;
  @Autowired private AdaptiveQueueRepository queueRepository;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;

  private UUID userId;
  private UUID sessionId;
  private UUID trackId;

  @BeforeEach
  void setUp() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          UserEntity user = new UserEntity();
          user.setUsername("signal_" + UUID.randomUUID().toString().substring(0, 8));
          user.setPasswordHash("$2a$10$dummyhash");
          user.setActive(true);
          user.setAdmin(false);
          em.persist(user);
          userId = user.getId();

          ItemEntity artist = new ItemEntity();
          artist.setType(ItemType.MusicArtist);
          artist.setName("Signal Artist");
          artist.setPath("artist:sig:" + UUID.randomUUID());
          em.persist(artist);

          ItemEntity album = new ItemEntity();
          album.setType(ItemType.MusicAlbum);
          album.setName("Signal Album");
          album.setPath("album:sig:" + UUID.randomUUID());
          album.setParent(artist);
          em.persist(album);

          ItemEntity track = new ItemEntity();
          track.setType(ItemType.AudioTrack);
          track.setName("Signal Track");
          track.setPath("audio:sig:" + UUID.randomUUID());
          track.setParent(album);
          em.persist(track);
          trackId = track.getId();

          em.flush();
          em.clear();
        });

    var session = sessionService.createSession(userId, Map.of("energy", 5));
    sessionId = session.getId();
  }

  @Test
  @Feature(id = "FEAT-SIGNALS", ac = "AC-01")
  @DisplayName("AC-01: Persisted signal has correct fields")
  void persistSignalCreatesEntity() {
    var result =
        signalService.persistSignal(
            sessionId, "PLAY_START", trackId, null, Map.of("source", "radio"));

    assertThat(result.signal()).isNotNull();
    assertThat(result.signal().getSignalType()).isEqualTo("PLAY_START");
    assertThat(result.signal().getSession().getId()).isEqualTo(sessionId);
    assertThat(result.signal().getItem().getId()).isEqualTo(trackId);
    assertThat(result.userId()).isEqualTo(userId);
  }

  @Test
  @Feature(id = "FEAT-SIGNALS", ac = "AC-02")
  @DisplayName("AC-02: Queue with no entries is detected as low")
  void isQueueLowTrue() {
    assertThat(signalService.isQueueLow(sessionId, 8)).isTrue();
  }

  @Test
  @Feature(id = "FEAT-SIGNALS", ac = "AC-03")
  @DisplayName("AC-03: Skip pattern detected and diluted correctly")
  void hasSkipPattern() {
    signalService.persistSignal(sessionId, "SKIP_EARLY", trackId, null, Map.of());
    signalService.persistSignal(sessionId, "THUMBS_DOWN", trackId, null, Map.of());

    assertThat(signalService.hasSkipPattern(sessionId)).isTrue();

    for (int i = 0; i < 10; i++) {
      signalService.persistSignal(sessionId, "PLAY_START", trackId, null, Map.of());
    }

    assertThat(signalService.hasSkipPattern(sessionId)).isFalse();
  }
}
