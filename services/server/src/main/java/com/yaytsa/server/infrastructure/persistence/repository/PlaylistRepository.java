package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaylistRepository extends JpaRepository<PlaylistEntity, UUID> {
  List<PlaylistEntity> findAllByUserId(UUID userId);

  List<PlaylistEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
