package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import java.util.List;
import java.util.Optional;
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
          SELECT i.id, i.name, ar.name AS artist_name,
                 tf.bpm, tf.energy, tf.valence, tf.arousal,
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
}
