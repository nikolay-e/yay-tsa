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
@Table(name = "track_features")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TrackFeaturesEntity {

  @Id
  @Column(name = "track_id")
  private UUID trackId;

  @OneToOne(fetch = FetchType.LAZY)
  @MapsId
  @JoinColumn(name = "track_id", foreignKey = @ForeignKey(name = "fk_track_features_item"))
  private ItemEntity item;

  @Column private Float bpm;

  @Column(name = "bpm_confidence")
  private Float bpmConfidence;

  @Column(name = "musical_key", length = 20)
  private String musicalKey;

  @Column(name = "key_confidence")
  private Float keyConfidence;

  @Column private Float energy;

  @Column(name = "loudness_integrated")
  private Float loudnessIntegrated;

  @Column(name = "loudness_range")
  private Float loudnessRange;

  @Column(name = "average_loudness")
  private Float averageLoudness;

  @Column private Float valence;

  @Column private Float arousal;

  @Column private Float danceability;

  @Column(name = "vocal_instrumental")
  private Float vocalInstrumental;

  @Column(name = "voice_gender", length = 10)
  private String voiceGender;

  @Column(name = "spectral_complexity")
  private Float spectralComplexity;

  @Column private Float dissonance;

  @Column(name = "onset_rate")
  private Float onsetRate;

  @Column(name = "intro_duration_sec")
  private Float introDurationSec;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 1280)
  @Column(name = "embedding_discogs", columnDefinition = "vector(1280)")
  private float[] embeddingDiscogs;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 200)
  @Column(name = "embedding_musicnn", columnDefinition = "vector(200)")
  private float[] embeddingMusicnn;

  @Column(name = "extracted_at")
  private OffsetDateTime extractedAt;

  @Column(name = "extractor_version", length = 20)
  private String extractorVersion;
}
