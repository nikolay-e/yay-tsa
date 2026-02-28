package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ListeningSessionRepository extends JpaRepository<ListeningSessionEntity, UUID> {
  Optional<ListeningSessionEntity> findByUserIdAndEndedAtIsNull(UUID userId);
}
