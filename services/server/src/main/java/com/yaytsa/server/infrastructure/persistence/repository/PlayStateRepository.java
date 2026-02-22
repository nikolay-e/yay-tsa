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
      "UPDATE PlayStateEntity ps SET ps.playCount = ps.playCount + 1, ps.lastPlayedAt = :playedAt"
          + " WHERE ps.user.id = :userId AND ps.item.id = :itemId")
  int incrementPlayCount(UUID userId, UUID itemId, OffsetDateTime playedAt);
}
