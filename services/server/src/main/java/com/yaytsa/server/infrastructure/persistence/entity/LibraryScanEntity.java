package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "library_scans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LibraryScanEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "library_root", nullable = false, columnDefinition = "TEXT")
  private String libraryRoot;

  @Column(name = "scan_type", nullable = false, length = 20)
  private String scanType;

  @CreationTimestamp
  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "files_scanned")
  private Integer filesScanned = 0;

  @Column(name = "files_added")
  private Integer filesAdded = 0;

  @Column(name = "files_updated")
  private Integer filesUpdated = 0;

  @Column(name = "files_removed")
  private Integer filesRemoved = 0;

  @Column(name = "error_count")
  private Integer errorCount = 0;

  @Column(length = 20)
  private String status = "Running";

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;
}
