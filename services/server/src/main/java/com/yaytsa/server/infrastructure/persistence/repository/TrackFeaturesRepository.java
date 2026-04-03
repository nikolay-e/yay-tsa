package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackFeaturesRepository extends JpaRepository<TrackFeaturesEntity, UUID> {
  Optional<TrackFeaturesEntity> findByTrackId(UUID trackId);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.dissonance, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND (:energyMin IS NULL OR tf.energy >= :energyMin)
            AND (:energyMax IS NULL OR tf.energy <= :energyMax)
            AND (:bpmMin IS NULL OR tf.bpm >= :bpmMin)
            AND (:bpmMax IS NULL OR tf.bpm <= :bpmMax)
            AND (:valenceMin IS NULL OR tf.valence >= :valenceMin)
            AND (:valenceMax IS NULL OR tf.valence <= :valenceMax)
            AND (:arousalMin IS NULL OR tf.arousal >= :arousalMin)
            AND (:arousalMax IS NULL OR tf.arousal <= :arousalMax)
            AND (:vocalMax IS NULL OR tf.vocal_instrumental <= :vocalMax)
          ORDER BY random()
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> searchLibrary(
      @Param("energyMin") Float energyMin,
      @Param("energyMax") Float energyMax,
      @Param("bpmMin") Float bpmMin,
      @Param("bpmMax") Float bpmMax,
      @Param("valenceMin") Float valenceMin,
      @Param("valenceMax") Float valenceMax,
      @Param("arousalMin") Float arousalMin,
      @Param("arousalMax") Float arousalMax,
      @Param("vocalMax") Float vocalMax,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 1 - (tf.embedding_discogs <=> CAST(:refEmbedding AS vector)) AS similarity
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND i.id != :refTrackId
          ORDER BY tf.embedding_discogs <=> CAST(:refEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findSimilarTracks(
      @Param("refTrackId") UUID refTrackId,
      @Param("refEmbedding") String refEmbedding,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 1 - (tf.embedding_discogs <=> CAST(:refEmbedding AS vector)) AS similarity
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND i.id != :refTrackId
            AND tf.embedding_discogs IS NOT NULL
          ORDER BY tf.embedding_discogs <=> CAST(:refEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findSimilarTracksExact(
      @Param("refTrackId") UUID refTrackId,
      @Param("refEmbedding") String refEmbedding,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND i.id IN (:trackIds)
          """,
      nativeQuery = true)
  List<Object[]> findTrackDetailsById(@Param("trackIds") List<UUID> trackIds);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND ar.name = :artistName
          ORDER BY tf.energy DESC
          """,
      nativeQuery = true)
  List<Object[]> findByArtistName(@Param("artistName") String artistName);

  @Query(
      value =
          """
          SELECT DISTINCT i.id FROM items i
          JOIN item_genres ig ON ig.item_id = i.id
          JOIN genres g ON g.id = ig.genre_id
          WHERE LOWER(g.name) IN :genreNames
          """,
      nativeQuery = true)
  Set<UUID> findTrackIdsByGenreNames(@Param("genreNames") List<String> genreNames);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.dissonance, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          LEFT JOIN play_history ph ON ph.item_id = i.id AND ph.user_id = :userId
          WHERE i.type = 'AudioTrack'
            AND ph.id IS NULL
            AND (:energyMin IS NULL OR tf.energy >= :energyMin)
            AND (:energyMax IS NULL OR tf.energy <= :energyMax)
            AND (:bpmMin IS NULL OR tf.bpm >= :bpmMin)
            AND (:bpmMax IS NULL OR tf.bpm <= :bpmMax)
            AND (:valenceMin IS NULL OR tf.valence >= :valenceMin)
            AND (:valenceMax IS NULL OR tf.valence <= :valenceMax)
          ORDER BY random()
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findNeverPlayedTracks(
      @Param("userId") UUID userId,
      @Param("energyMin") Float energyMin,
      @Param("energyMax") Float energyMax,
      @Param("bpmMin") Float bpmMin,
      @Param("bpmMax") Float bpmMax,
      @Param("valenceMin") Float valenceMin,
      @Param("valenceMax") Float valenceMax,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 1 - (tf.embedding_mert <=> CAST(:refEmbedding AS vector)) AS similarity
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND i.id != :refTrackId
            AND tf.embedding_mert IS NOT NULL
          ORDER BY tf.embedding_mert <=> CAST(:refEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findSimilarTracksByMert(
      @Param("refTrackId") UUID refTrackId,
      @Param("refEmbedding") String refEmbedding,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 1 - (tf.embedding_clap <=> CAST(:queryEmbedding AS vector)) AS similarity
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND tf.embedding_clap IS NOT NULL
          ORDER BY tf.embedding_clap <=> CAST(:queryEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findTracksByTextEmbedding(
      @Param("queryEmbedding") String queryEmbedding, @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 1 - (tf.embedding_mert <=> CAST(:userEmbedding AS vector)) AS similarity
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND tf.embedding_mert IS NOT NULL
            AND i.id NOT IN (:excludeIds)
          ORDER BY tf.embedding_mert <=> CAST(:userEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findTracksByUserEmbedding(
      @Param("userEmbedding") String userEmbedding,
      @Param("excludeIds") List<UUID> excludeIds,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT tf.track_id, tf.embedding_mert
          FROM track_features tf
          JOIN items i ON i.id = tf.track_id
          WHERE i.type = 'AudioTrack'
            AND tf.embedding_mert IS NOT NULL
            AND tf.track_id NOT IN (:excludeIds)
          ORDER BY tf.embedding_mert <=> CAST(:userEmbedding AS vector)
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findDiscoveryEmbeddingsByUserProfile(
      @Param("userEmbedding") String userEmbedding,
      @Param("excludeIds") List<UUID> excludeIds,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT COUNT(*) FROM track_features
          WHERE embedding_mert IS NOT NULL
          """,
      nativeQuery = true)
  long countWithEmbeddings();

  @Query(
      value =
          """
          SELECT tf.track_id FROM track_features tf
          LEFT JOIN (
            SELECT track_id, MAX(affinity_score) AS max_affinity
            FROM user_track_affinity
            WHERE affinity_score > 0
            GROUP BY track_id
          ) agg ON agg.track_id = tf.track_id
          WHERE tf.embedding_discogs IS NOT NULL
            AND tf.embedding_mert IS NULL
          ORDER BY agg.max_affinity DESC NULLS LAST
          LIMIT :lim
          """,
      nativeQuery = true)
  List<UUID> findTrackIdsWithoutEmbeddings(@Param("lim") int limit);

  @Query(
      value =
          """
          SELECT COUNT(*) FROM track_features
          WHERE embedding_discogs IS NOT NULL
            AND embedding_mert IS NULL
          """,
      nativeQuery = true)
  long countTracksWithoutEmbeddings();

  @Query(
      value =
          """
          SELECT tf.track_id, tf.embedding_mert, uta.affinity_score
          FROM track_features tf
          JOIN user_track_affinity uta ON uta.track_id = tf.track_id
          WHERE tf.embedding_mert IS NOT NULL
            AND uta.user_id = :userId
            AND uta.affinity_score > 0
          ORDER BY uta.affinity_score DESC
          """,
      nativeQuery = true)
  List<Object[]> findMertEmbeddingsForUserWithPositiveAffinity(@Param("userId") UUID userId);

  @Query(
      value =
          """
          SELECT tf.track_id, tf.embedding_mert,
                 (COALESCE(ps.play_count, 0) * 0.3
                  + CASE WHEN ps.is_favorite THEN 2.0 ELSE 0.0 END
                 ) AS derived_affinity
          FROM track_features tf
          JOIN play_state ps ON ps.item_id = tf.track_id AND ps.user_id = :userId
          WHERE tf.embedding_mert IS NOT NULL
            AND (ps.play_count > 0 OR ps.is_favorite = true)
          ORDER BY derived_affinity DESC, ps.last_played_at DESC NULLS LAST
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findMertEmbeddingsForUserPlayHistory(
      @Param("userId") UUID userId, @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT tf.track_id, tf.embedding_mert
          FROM track_features tf
          WHERE tf.track_id IN (:trackIds)
            AND tf.embedding_mert IS NOT NULL
          """,
      nativeQuery = true)
  List<Object[]> findMertEmbeddingsByTrackIds(@Param("trackIds") List<UUID> trackIds);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.dissonance, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE i.type = 'AudioTrack'
            AND EXISTS (
              SELECT 1 FROM item_genres ig
              JOIN genres g ON g.id = ig.genre_id
              WHERE ig.item_id = i.id AND LOWER(g.name) IN (:genreNames)
            )
            AND (:energyMin IS NULL OR tf.energy >= :energyMin)
            AND (:energyMax IS NULL OR tf.energy <= :energyMax)
            AND (:valenceMin IS NULL OR tf.valence >= :valenceMin)
            AND (:valenceMax IS NULL OR tf.valence <= :valenceMax)
          ORDER BY random()
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> searchLibraryByGenre(
      @Param("genreNames") List<String> genreNames,
      @Param("energyMin") Float energyMin,
      @Param("energyMax") Float energyMax,
      @Param("valenceMin") Float valenceMin,
      @Param("valenceMax") Float valenceMax,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT i.id, i.name, ar.name AS artist_name, al.name AS album_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal, tf.danceability,
                 tf.vocal_instrumental, tf.dissonance, tf.musical_key
          FROM items i
          JOIN track_features tf ON tf.track_id = i.id
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          LEFT JOIN play_history ph ON ph.item_id = i.id AND ph.user_id = :userId
          WHERE i.type = 'AudioTrack'
            AND ph.id IS NULL
            AND EXISTS (
              SELECT 1 FROM item_genres ig
              JOIN genres g ON g.id = ig.genre_id
              WHERE ig.item_id = i.id AND LOWER(g.name) IN (:genreNames)
            )
            AND (:energyMin IS NULL OR tf.energy >= :energyMin)
            AND (:energyMax IS NULL OR tf.energy <= :energyMax)
          ORDER BY random()
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> findNeverPlayedTracksByGenre(
      @Param("userId") UUID userId,
      @Param("genreNames") List<String> genreNames,
      @Param("energyMin") Float energyMin,
      @Param("energyMax") Float energyMax,
      @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT ig.item_id, g.name
          FROM item_genres ig
          JOIN genres g ON g.id = ig.genre_id
          WHERE ig.item_id IN (:trackIds)
          """,
      nativeQuery = true)
  List<Object[]> findGenresByTrackIds(@Param("trackIds") List<UUID> trackIds);
}
