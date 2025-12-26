package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "play_state",
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_user_item", columnNames = {"user_id", "item_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlayStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_play_state_user"))
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false, foreignKey = @ForeignKey(name = "fk_play_state_item"))
    private ItemEntity item;

    @Column(name = "item_id", insertable = false, updatable = false)
    private UUID itemId;

    @Column(name = "is_favorite", nullable = false)
    private Boolean isFavorite = false;

    @Column(name = "play_count", nullable = false)
    private Integer playCount = 0;

    @Column(name = "last_played_at")
    private OffsetDateTime lastPlayedAt;

    @Column(name = "playback_position_ms", nullable = false)
    private Long playbackPositionMs = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
