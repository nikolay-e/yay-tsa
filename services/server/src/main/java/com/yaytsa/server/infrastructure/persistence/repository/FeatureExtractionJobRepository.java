package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.FeatureExtractionJobEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface FeatureExtractionJobRepository
    extends JpaRepository<FeatureExtractionJobEntity, UUID> {
  @Query(
      "SELECT j FROM FeatureExtractionJobEntity j JOIN FETCH j.item WHERE j.status = :status ORDER"
          + " BY j.createdAt ASC")
  List<FeatureExtractionJobEntity> findByStatusWithItem(String status, Pageable pageable);

  List<FeatureExtractionJobEntity> findByStatusOrderByCreatedAtAsc(
      String status, Pageable pageable);

  Optional<FeatureExtractionJobEntity> findByItemId(UUID itemId);

  long countByStatus(String status);
}
