package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.FeatureExtractionJobEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeatureExtractionJobRepository
    extends JpaRepository<FeatureExtractionJobEntity, UUID> {
  List<FeatureExtractionJobEntity> findByStatusOrderByCreatedAtAsc(
      String status, Pageable pageable);

  Optional<FeatureExtractionJobEntity> findByItemId(UUID itemId);

  long countByStatus(String status);
}
