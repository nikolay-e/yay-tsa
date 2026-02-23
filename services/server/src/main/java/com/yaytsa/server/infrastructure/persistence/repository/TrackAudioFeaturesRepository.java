package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.TrackAudioFeaturesEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackAudioFeaturesRepository
    extends JpaRepository<TrackAudioFeaturesEntity, UUID> {

  @Query(
      "SELECT at.itemId FROM AudioTrackEntity at "
          + "WHERE at.itemId NOT IN (SELECT taf.itemId FROM TrackAudioFeaturesEntity taf) "
          + "ORDER BY at.itemId")
  List<UUID> findUnanalyzedTrackIds(@Param("limit") int limit);

  @Query(
      value =
          "SELECT at.item_id FROM audio_tracks at "
              + "WHERE at.item_id NOT IN (SELECT taf.item_id FROM track_audio_features taf) "
              + "ORDER BY at.item_id LIMIT :limit",
      nativeQuery = true)
  List<UUID> findUnanalyzedTrackIdsNative(@Param("limit") int limit);

  @Query(
      "SELECT taf FROM TrackAudioFeaturesEntity taf "
          + "WHERE (:mood IS NULL OR taf.mood = :mood) "
          + "AND (:language IS NULL OR taf.language = :language) "
          + "AND (:minEnergy IS NULL OR taf.energy >= :minEnergy) "
          + "AND (:maxEnergy IS NULL OR taf.energy <= :maxEnergy)")
  List<TrackAudioFeaturesEntity> findByFilters(
      @Param("mood") String mood,
      @Param("language") String language,
      @Param("minEnergy") Short minEnergy,
      @Param("maxEnergy") Short maxEnergy);

  @Query("SELECT DISTINCT taf.mood FROM TrackAudioFeaturesEntity taf WHERE taf.mood IS NOT NULL")
  List<String> findDistinctMoods();

  @Query(
      "SELECT DISTINCT taf.language FROM TrackAudioFeaturesEntity taf WHERE taf.language IS NOT NULL")
  List<String> findDistinctLanguages();

  @Query("SELECT COUNT(taf) FROM TrackAudioFeaturesEntity taf")
  long countAnalyzed();

  @Query("SELECT COUNT(at) FROM AudioTrackEntity at")
  long countTotalTracks();
}
