package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlayStateEntity;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayStateRepository extends JpaRepository<PlayStateEntity, UUID> {
  Optional<PlayStateEntity> findByUserIdAndItemId(UUID userId, UUID itemId);

  List<PlayStateEntity> findAllByUserIdAndIsFavoriteTrue(UUID userId);

  @Query("SELECT ps FROM PlayStateEntity ps WHERE ps.user.id = :userId AND ps.item.id IN :itemIds")
  List<PlayStateEntity> findAllByUserIdAndItemIdIn(
      @Param("userId") UUID userId, @Param("itemIds") Collection<UUID> itemIds);

  @Modifying
  @Query(
      value =
          "WITH shift AS ( UPDATE play_state SET favorite_position = favorite_position + 1,"
              + " updated_at = NOW() WHERE user_id = :userId AND is_favorite = true AND item_id !="
              + " :itemId::uuid ) INSERT INTO play_state (id, user_id, item_id, is_favorite,"
              + " play_count, playback_position_ms, favorited_at, favorite_position, created_at,"
              + " updated_at) VALUES (gen_random_uuid(), :userId, :itemId, true, 0, 0, NOW(), 1,"
              + " NOW(), NOW()) ON CONFLICT (user_id, item_id) DO UPDATE SET is_favorite = true,"
              + " favorited_at = COALESCE(play_state.favorited_at, NOW()), favorite_position = 1,"
              + " updated_at = NOW()",
      nativeQuery = true)
  void upsertMarkFavorite(@Param("userId") UUID userId, @Param("itemId") UUID itemId);

  @Modifying
  @Query(
      value =
          "INSERT INTO play_state (id, user_id, item_id, is_favorite, play_count,"
              + " playback_position_ms, created_at, updated_at) VALUES (gen_random_uuid(),"
              + " :userId, :itemId, false, 0, 0, NOW(), NOW()) ON CONFLICT (user_id, item_id) DO"
              + " UPDATE SET is_favorite = false, favorited_at = NULL, favorite_position = NULL,"
              + " updated_at = NOW()",
      nativeQuery = true)
  void upsertUnmarkFavorite(@Param("userId") UUID userId, @Param("itemId") UUID itemId);

  @Modifying
  @Query(
      value =
          "UPDATE play_state ps SET favorite_position = v.pos, updated_at = NOW()"
              + " FROM (SELECT unnest(:itemIds\\:\\:uuid[]) AS item_id,"
              + " generate_series(1, array_length(:itemIds\\:\\:uuid[], 1)) AS pos) v"
              + " WHERE ps.user_id = :userId AND ps.item_id = v.item_id AND ps.is_favorite = true",
      nativeQuery = true)
  void batchUpdateFavoritePositions(@Param("userId") UUID userId, @Param("itemIds") UUID[] itemIds);

  @Modifying
  @Query(
      value =
          "UPDATE play_state ps SET favorite_position = sub.new_pos, updated_at = NOW()"
              + " FROM (SELECT item_id, ROW_NUMBER() OVER ("
              + " ORDER BY favorite_position ASC NULLS LAST, favorited_at ASC NULLS LAST)"
              + " + :offset AS new_pos FROM play_state"
              + " WHERE user_id = :userId AND is_favorite = true"
              + " AND item_id != ALL(:excludeIds\\:\\:uuid[])) sub"
              + " WHERE ps.user_id = :userId AND ps.item_id = sub.item_id"
              + " AND ps.is_favorite = true",
      nativeQuery = true)
  void renumberRemainingFavorites(
      @Param("userId") UUID userId,
      @Param("excludeIds") UUID[] excludeIds,
      @Param("offset") int offset);

  @Modifying
  @Query(
      value =
          "INSERT INTO play_state (id, user_id, item_id, is_favorite, play_count, last_played_at,"
              + " playback_position_ms, created_at, updated_at) VALUES (gen_random_uuid(), :userId,"
              + " :itemId, false, 1, :playedAt, 0, NOW(), NOW()) ON CONFLICT (user_id, item_id) DO"
              + " UPDATE SET play_count = play_state.play_count + 1, last_played_at = :playedAt,"
              + " updated_at = NOW()",
      nativeQuery = true)
  void upsertPlayCount(
      @Param("userId") UUID userId,
      @Param("itemId") UUID itemId,
      @Param("playedAt") OffsetDateTime playedAt);

  @Modifying
  @Query(
      value =
          "INSERT INTO play_state (id, user_id, item_id, is_favorite, play_count,"
              + " playback_position_ms, created_at, updated_at) VALUES (gen_random_uuid(), :userId,"
              + " :itemId, false, 0, :positionMs, NOW(), NOW()) ON CONFLICT (user_id, item_id) DO"
              + " UPDATE SET playback_position_ms = :positionMs, updated_at = NOW()",
      nativeQuery = true)
  void upsertPlaybackPosition(
      @Param("userId") UUID userId,
      @Param("itemId") UUID itemId,
      @Param("positionMs") long positionMs);
}
