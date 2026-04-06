package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "api_tokens",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id"}))
public class ApiTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @Column(unique = true, nullable = false, length = 64)
  private String token;

  @Column(name = "device_id", nullable = false, length = 255)
  private String deviceId;

  @Column(name = "device_name", length = 255)
  private String deviceName;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "last_used_at")
  private OffsetDateTime lastUsedAt;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;

  @Column(nullable = false)
  private boolean revoked;

  public boolean isValid() {
    if (revoked) {
      return false;
    }
    if (expiresAt != null && OffsetDateTime.now().isAfter(expiresAt)) {
      return false;
    }
    return user != null && user.isActive();
  }
}
