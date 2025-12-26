package com.yaytsa.server.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "sessions",
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_user_device", columnNames = {"user_id", "device_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sessions_user"))
    private UserEntity user;

    @Column(name = "device_id", nullable = false, length = 255)
    private String deviceId;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Column(name = "client_version", length = 50)
    private String clientVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "now_playing_item_id", foreignKey = @ForeignKey(name = "fk_sessions_now_playing"))
    private ItemEntity nowPlayingItem;

    @Column(name = "position_ms", nullable = false)
    private Long positionMs = 0L;

    @Column(nullable = false)
    private Boolean paused = false;

    @Column(name = "volume_level", nullable = false)
    private Integer volumeLevel = 100;

    @Column(name = "is_muted", nullable = false)
    private Boolean isMuted = false;

    @Column(name = "repeat_mode", length = 20)
    private String repeatMode;

    @Column(name = "is_shuffled", nullable = false)
    private Boolean isShuffled = false;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
