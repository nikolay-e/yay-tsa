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
          "INSERT INTO play_state (id, user_id, item_id, is_favorite, play_count,"
              + " playback_position_ms, created_at, updated_at) VALUES (gen_random_uuid(), :userId,"
              + " :itemId, :isFavorite, 0, 0, NOW(), NOW()) ON CONFLICT (user_id, item_id) DO"
              + " UPDATE SET is_favorite = :isFavorite, updated_at = NOW()",
      nativeQuery = true)
  void upsertFavorite(
      @Param("userId") UUID userId,
      @Param("itemId") UUID itemId,
      @Param("isFavorite") boolean isFavorite);

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
