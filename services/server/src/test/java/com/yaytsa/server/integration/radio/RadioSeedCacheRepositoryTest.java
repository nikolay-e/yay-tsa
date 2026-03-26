package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity;
import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity.RadioSeedCacheId;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.RadioSeedCacheRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
@ActiveProfiles("tc")
@DisplayName("RadioSeedCacheRepository")
class RadioSeedCacheRepositoryTest {

  @Autowired private RadioSeedCacheRepository seedCacheRepository;
  @Autowired private EntityManager em;

  private UUID userId;
  private UUID trackId1;
  private UUID trackId2;
  private UUID trackId3;

  @BeforeEach
  void setUp() {
    seedCacheRepository.deleteAll();

    UserEntity user = new UserEntity();
    user.setUsername("testuser_" + UUID.randomUUID().toString().substring(0, 8));
    user.setPasswordHash("$2a$10$dummyhash");
    user.setActive(true);
    user.setAdmin(false);
    em.persist(user);
    userId = user.getId();

    trackId1 = createTrackItem("Track One");
    trackId2 = createTrackItem("Track Two");
    trackId3 = createTrackItem("Track Three");

    em.flush();
    em.clear();
  }

  @Test
  @DisplayName("stores seeds and retrieves them in position order with correct timestamp type")
  void storesAndRetrievesSeedsInOrder() {
    insertCacheEntry(userId, (short) 0, trackId3);
    insertCacheEntry(userId, (short) 1, trackId1);
    insertCacheEntry(userId, (short) 2, trackId2);
    em.flush();
    em.clear();

    List<UUID> trackIds = seedCacheRepository.findTrackIdsByUserId(userId);
    assertThat(trackIds).containsExactly(trackId3, trackId1, trackId2);

    Instant computedAt = seedCacheRepository.findComputedAtByUserId(userId);
    assertThat(computedAt).isNotNull().isInstanceOf(Instant.class);
    assertThat(computedAt).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(5));
  }

  @Test
  @DisplayName("per-user deletion doesn't affect other users")
  void perUserDeletionIsIsolated() {
    UserEntity otherUser = new UserEntity();
    otherUser.setUsername("other_" + UUID.randomUUID().toString().substring(0, 8));
    otherUser.setPasswordHash("$2a$10$dummyhash");
    otherUser.setActive(true);
    otherUser.setAdmin(false);
    em.persist(otherUser);
    em.flush();
    UUID otherUserId = otherUser.getId();

    insertCacheEntry(userId, (short) 0, trackId1);
    insertCacheEntry(otherUserId, (short) 0, trackId2);
    em.flush();
    em.clear();

    seedCacheRepository.deleteByUserId(userId);

    assertThat(seedCacheRepository.findTrackIdsByUserId(userId)).isEmpty();
    assertThat(seedCacheRepository.findTrackIdsByUserId(otherUserId)).hasSize(1);
  }

  @Test
  @DisplayName("deleteAllCache clears everything")
  void deleteAllClearsEverything() {
    insertCacheEntry(userId, (short) 0, trackId1);
    insertCacheEntry(userId, (short) 1, trackId2);
    em.flush();
    em.clear();

    seedCacheRepository.deleteAllCache();

    assertThat(seedCacheRepository.findTrackIdsByUserId(userId)).isEmpty();
  }

  @Test
  @DisplayName("returns empty/null for unknown user")
  void emptyForUnknownUser() {
    assertThat(seedCacheRepository.findTrackIdsByUserId(UUID.randomUUID())).isEmpty();
    assertThat(seedCacheRepository.findComputedAtByUserId(UUID.randomUUID())).isNull();
  }

  private UUID createTrackItem(String name) {
    ItemEntity item = new ItemEntity();
    item.setType(ItemType.AudioTrack);
    item.setName(name);
    item.setPath("audio:" + UUID.randomUUID());
    em.persist(item);
    return item.getId();
  }

  private void insertCacheEntry(UUID uid, short position, UUID trackId) {
    var entity = new RadioSeedCacheEntity();
    entity.setId(new RadioSeedCacheId(uid, position));
    entity.setTrackId(trackId);
    entity.setComputedAt(OffsetDateTime.now());
    em.persist(entity);
  }
}
