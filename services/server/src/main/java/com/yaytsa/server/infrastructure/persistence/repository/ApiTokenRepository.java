package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ApiTokenEntity;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, UUID> {

  @Query(
      "SELECT t FROM ApiTokenEntity t JOIN FETCH t.user WHERE t.token = :token AND t.revoked ="
          + " false")
  Optional<ApiTokenEntity> findByTokenAndNotRevoked(@Param("token") String token);

  Optional<ApiTokenEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);

  void deleteByUserIdAndDeviceId(UUID userId, String deviceId);

  @Modifying
  @Query("UPDATE ApiTokenEntity t SET t.lastUsedAt = :usedAt WHERE t.id = :tokenId")
  void updateLastUsedAt(UUID tokenId, OffsetDateTime usedAt);

  @Modifying
  @Query(
      "DELETE FROM ApiTokenEntity t WHERE t.revoked = true OR (t.expiresAt IS NOT NULL AND"
          + " t.expiresAt < :now)")
  int deleteExpiredTokens(@Param("now") OffsetDateTime now);
}
