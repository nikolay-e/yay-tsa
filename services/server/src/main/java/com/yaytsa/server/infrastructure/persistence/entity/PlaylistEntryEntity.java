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
@Table(
    name = "playlist_entries",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "unique_playlist_position",
          columnNames = {"playlist_id", "position"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "playlist_id", nullable = false)
  private UUID playlistId;

  @Column(name = "item_id", nullable = false)
  private UUID itemId;

  @Column(nullable = false)
  private Integer position;

  @CreationTimestamp
  @Column(name = "added_at", nullable = false, updatable = false)
  private OffsetDateTime addedAt;
}
