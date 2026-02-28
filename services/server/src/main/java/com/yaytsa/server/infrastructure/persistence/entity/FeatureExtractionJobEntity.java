package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "feature_extraction_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FeatureExtractionJobEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "track_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_feature_extraction_job_item"))
  private ItemEntity item;

  @Column(length = 20, nullable = false)
  private String status = "PENDING";

  @Column(nullable = false)
  private int attempts = 0;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
