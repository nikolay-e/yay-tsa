package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByUsername(String username);

  boolean existsByUsername(String username);

  boolean existsByEmail(String email);

  @Modifying
  @Query("UPDATE UserEntity u SET u.lastLoginAt = :loginAt WHERE u.id = :userId")
  void updateLastLoginAt(UUID userId, OffsetDateTime loginAt);
}
