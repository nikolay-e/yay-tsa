package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "track_audio_features")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TrackAudioFeaturesEntity {

  @Id
  @Column(name = "item_id")
  private UUID itemId;

  @OneToOne(fetch = FetchType.LAZY)
  @MapsId
  @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_taf_item"))
  private ItemEntity item;

  @Column(length = 50)
  private String mood;

  @Column
  private Short energy;

  @Column(length = 10)
  private String language;

  @Column(columnDefinition = "TEXT")
  private String themes;

  @Column
  private Short valence;

  @Column
  private Short danceability;

  @Column(name = "analyzed_at", nullable = false)
  private OffsetDateTime analyzedAt;

  @Column(name = "llm_provider", length = 50)
  private String llmProvider;

  @Column(name = "llm_model", length = 100)
  private String llmModel;

  @Column(name = "raw_response", columnDefinition = "TEXT")
  private String rawResponse;
}
