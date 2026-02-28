package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "taste_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TasteProfileEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @OneToOne(fetch = FetchType.LAZY)
  @MapsId
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_taste_profile_user"))
  private UserEntity user;

  @Column(columnDefinition = "jsonb", nullable = false)
  private String profile = "{}";

  @Column(name = "summary_text", columnDefinition = "TEXT")
  private String summaryText;

  @Column(name = "rebuilt_at")
  private OffsetDateTime rebuiltAt;
}
