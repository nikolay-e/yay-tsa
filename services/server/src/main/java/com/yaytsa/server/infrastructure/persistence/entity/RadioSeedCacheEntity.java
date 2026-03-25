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
@Table(name = "radio_seed_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RadioSeedCacheEntity {

  @EmbeddedId private RadioSeedCacheId id;

  @Column(name = "track_id", nullable = false)
  private UUID trackId;

  @Column(name = "computed_at", nullable = false)
  private OffsetDateTime computedAt;

  @Embeddable
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RadioSeedCacheId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "position", nullable = false)
    private short position;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof RadioSeedCacheId that)) return false;
      return position == that.position && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, position);
    }
  }
}
