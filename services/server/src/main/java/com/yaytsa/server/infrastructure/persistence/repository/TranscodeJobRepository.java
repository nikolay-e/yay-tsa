package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.TranscodeJobEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TranscodeJobRepository extends JpaRepository<TranscodeJobEntity, UUID> {
  List<TranscodeJobEntity> findByStatus(String status);

  List<TranscodeJobEntity> findBySessionId(UUID sessionId);

  long countByStatus(String status);
}
