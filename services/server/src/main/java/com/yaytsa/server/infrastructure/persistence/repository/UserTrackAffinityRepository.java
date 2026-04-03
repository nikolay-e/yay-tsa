package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity.AffinityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserTrackAffinityRepository
    extends JpaRepository<UserTrackAffinityEntity, AffinityId> {

  @Query(
      """
      SELECT a FROM UserTrackAffinityEntity a
      WHERE a.id.userId = :userId AND a.affinityScore > 0
      ORDER BY a.affinityScore DESC
      """)
  List<UserTrackAffinityEntity> findPositiveByUserId(@Param("userId") UUID userId);

  @Query(
      """
      SELECT a.id.trackId FROM UserTrackAffinityEntity a
      WHERE a.id.userId = :userId AND a.thumbsDownCount > 0
      """)
  List<UUID> findDislikedTrackIds(@Param("userId") UUID userId);

  @Query(
      """
      SELECT a FROM UserTrackAffinityEntity a
      WHERE a.id.userId = :userId AND a.affinityScore > 0
      ORDER BY a.affinityScore DESC
      """)
  List<UserTrackAffinityEntity> findTopPositiveByUserId(
      @Param("userId") UUID userId, Pageable pageable);

  @Modifying
  @Query(
      value =
          """
          UPDATE user_track_affinity
          SET play_count = play_count + 1,
              affinity_score = (completion_count) * 1.0 + (play_count + 1) * 0.3
                  + skip_count * (-1.5) + thumbs_up_count * 2.0 + thumbs_down_count * (-3.0),
              last_signal_at = NOW(), updated_at = NOW()
          WHERE user_id = :userId AND track_id = :trackId
          """,
      nativeQuery = true)
  int incrementPlayCount(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

  @Modifying
  @Query(
      value =
          """
          UPDATE user_track_affinity
          SET completion_count = completion_count + 1,
              affinity_score = (completion_count + 1) * 1.0 + play_count * 0.3
                  + skip_count * (-1.5) + thumbs_up_count * 2.0 + thumbs_down_count * (-3.0),
              last_signal_at = NOW(), updated_at = NOW()
          WHERE user_id = :userId AND track_id = :trackId
          """,
      nativeQuery = true)
  int incrementCompletionCount(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

  @Modifying
  @Query(
      value =
          """
          UPDATE user_track_affinity
          SET skip_count = skip_count + 1,
              affinity_score = completion_count * 1.0 + play_count * 0.3
                  + (skip_count + 1) * (-1.5) + thumbs_up_count * 2.0 + thumbs_down_count * (-3.0),
              last_signal_at = NOW(), updated_at = NOW()
          WHERE user_id = :userId AND track_id = :trackId
          """,
      nativeQuery = true)
  int incrementSkipCount(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

  @Modifying
  @Query(
      value =
          """
          UPDATE user_track_affinity
          SET thumbs_up_count = thumbs_up_count + 1,
              affinity_score = completion_count * 1.0 + play_count * 0.3
                  + skip_count * (-1.5) + (thumbs_up_count + 1) * 2.0 + thumbs_down_count * (-3.0),
              last_signal_at = NOW(), updated_at = NOW()
          WHERE user_id = :userId AND track_id = :trackId
          """,
      nativeQuery = true)
  int incrementThumbsUpCount(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

  @Modifying
  @Query(
      value =
          """
          UPDATE user_track_affinity
          SET thumbs_down_count = thumbs_down_count + 1,
              affinity_score = completion_count * 1.0 + play_count * 0.3
                  + skip_count * (-1.5) + thumbs_up_count * 2.0 + (thumbs_down_count + 1) * (-3.0),
              last_signal_at = NOW(), updated_at = NOW()
          WHERE user_id = :userId AND track_id = :trackId
          """,
      nativeQuery = true)
  int incrementThumbsDownCount(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

  @Query(
      value =
          """
          SELECT DISTINCT artist.name
          FROM user_track_affinity a
          JOIN items track ON track.id = a.track_id
          JOIN items album ON album.id = track.parent_id
          JOIN items artist ON artist.id = album.parent_id
          WHERE a.user_id = :userId AND a.thumbs_down_count > 0
          """,
      nativeQuery = true)
  List<String> findDislikedArtistNames(@Param("userId") UUID userId);
}
