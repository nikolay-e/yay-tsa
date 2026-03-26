package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity;
import com.yaytsa.server.infrastructure.persistence.entity.RadioSeedCacheEntity.RadioSeedCacheId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RadioSeedCacheRepository
    extends JpaRepository<RadioSeedCacheEntity, RadioSeedCacheId> {

  @Query(
      value = "SELECT track_id FROM radio_seed_cache WHERE user_id = :userId ORDER BY position",
      nativeQuery = true)
  List<UUID> findTrackIdsByUserId(@Param("userId") UUID userId);

  @Query(
      value = "SELECT computed_at FROM radio_seed_cache WHERE user_id = :userId LIMIT 1",
      nativeQuery = true)
  Instant findComputedAtByUserId(@Param("userId") UUID userId);

  @Modifying
  @Query(value = "DELETE FROM radio_seed_cache WHERE user_id = :userId", nativeQuery = true)
  void deleteByUserId(@Param("userId") UUID userId);

  @Modifying
  @Query(value = "DELETE FROM radio_seed_cache", nativeQuery = true)
  void deleteAllCache();
}
