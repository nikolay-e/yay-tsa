package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ApiTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
