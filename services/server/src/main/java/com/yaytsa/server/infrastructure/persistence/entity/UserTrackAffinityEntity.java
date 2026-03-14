package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_track_affinity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserTrackAffinityEntity {

  @EmbeddedId private AffinityId id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("userId")
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_uta_user"))
  private UserEntity user;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("trackId")
  @JoinColumn(name = "track_id", foreignKey = @ForeignKey(name = "fk_uta_track"))
  private ItemEntity track;

  @Column(name = "affinity_score", nullable = false)
  private double affinityScore;

  @Column(name = "play_count", nullable = false)
  private int playCount;

  @Column(name = "completion_count", nullable = false)
  private int completionCount;

  @Column(name = "skip_count", nullable = false)
  private int skipCount;

  @Column(name = "thumbs_up_count", nullable = false)
  private int thumbsUpCount;

  @Column(name = "thumbs_down_count", nullable = false)
  private int thumbsDownCount;

  @Column(name = "total_listen_sec", nullable = false)
  private int totalListenSec;

  @Column(name = "last_signal_at")
  private OffsetDateTime lastSignalAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Embeddable
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AffinityId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "track_id")
    private UUID trackId;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AffinityId that)) return false;
      return Objects.equals(userId, that.userId) && Objects.equals(trackId, that.trackId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, trackId);
    }
  }
}
