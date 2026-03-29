package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import jakarta.persistence.EntityManager;
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
@DisplayName("TrackFeatures native queries")
class TrackFeaturesNativeQueryTest {

  @Autowired private TrackFeaturesRepository trackFeaturesRepository;
  @Autowired private EntityManager em;

  private UUID userId;
  private UUID highAffinityTrackId;
  private UUID lowAffinityTrackId;
  private UUID noEmbeddingTrackId;

  @BeforeEach
  void setUp() {
    UserEntity user = new UserEntity();
    user.setUsername("nqtest_" + UUID.randomUUID().toString().substring(0, 8));
    user.setPasswordHash("$2a$10$dummyhash");
    user.setActive(true);
    user.setAdmin(false);
    em.persist(user);
    userId = user.getId();

    ItemEntity artist = new ItemEntity();
    artist.setType(ItemType.MusicArtist);
    artist.setName("NQ Artist");
    artist.setPath("artist:nq:" + UUID.randomUUID());
    em.persist(artist);

    ItemEntity album = new ItemEntity();
    album.setType(ItemType.MusicAlbum);
    album.setName("NQ Album");
    album.setPath("album:nq:" + UUID.randomUUID());
    album.setParent(artist);
    em.persist(album);

    highAffinityTrackId = createTrack(album, "High Affinity", generateEmbedding(768, 0.1f));
    lowAffinityTrackId = createTrack(album, "Low Affinity", generateEmbedding(768, 0.5f));
    noEmbeddingTrackId = createTrack(album, "No Embedding", null);

    insertAffinity(userId, highAffinityTrackId, 0.9);
    insertAffinity(userId, lowAffinityTrackId, 0.3);
    insertAffinity(userId, noEmbeddingTrackId, 0.8);

    em.flush();
    em.clear();
  }

  @Test
  @DisplayName(
      "returns affinity tracks with embeddings, ordered by affinity, excludes no-embedding")
  void affinityQueryReturnsCorrectTracksInOrder() {
    List<Object[]> results =
        trackFeaturesRepository.findMertEmbeddingsForUserWithPositiveAffinity(userId);

    List<UUID> ids = results.stream().map(r -> (UUID) r[0]).toList();
    assertThat(ids).containsExactly(highAffinityTrackId, lowAffinityTrackId);
    assertThat(ids).doesNotContain(noEmbeddingTrackId);

    double firstAffinity = ((Number) results.get(0)[2]).doubleValue();
    double secondAffinity = ((Number) results.get(1)[2]).doubleValue();
    assertThat(firstAffinity).isGreaterThan(secondAffinity);
  }

  @Test
  @DisplayName("returns empty for unknown user")
  void affinityQueryEmptyForUnknownUser() {
    assertThat(
            trackFeaturesRepository.findMertEmbeddingsForUserWithPositiveAffinity(
                UUID.randomUUID()))
        .isEmpty();
  }

  private UUID createTrack(ItemEntity album, String name, float[] embedding) {
    ItemEntity track = new ItemEntity();
    track.setType(ItemType.AudioTrack);
    track.setName(name);
    track.setPath("audio:nq:" + UUID.randomUUID());
    track.setParent(album);
    em.persist(track);

    TrackFeaturesEntity features = new TrackFeaturesEntity();
    features.setItem(track);
    features.setTrackId(track.getId());
    features.setEmbeddingMert(embedding);
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
