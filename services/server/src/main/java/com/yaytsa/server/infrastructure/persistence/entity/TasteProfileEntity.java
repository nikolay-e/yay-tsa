package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String profile = "{}";

  @Column(name = "summary_text", columnDefinition = "TEXT")
  private String summaryText;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 768)
  @Column(name = "embedding_mert", columnDefinition = "vector(768)")
  private float[] embeddingMert;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 512)
  @Column(name = "embedding_clap", columnDefinition = "vector(512)")
  private float[] embeddingClap;

  @Column(name = "track_count", nullable = false)
  private int trackCount;

  @Column(name = "rebuilt_at")
  private OffsetDateTime rebuiltAt;
}
