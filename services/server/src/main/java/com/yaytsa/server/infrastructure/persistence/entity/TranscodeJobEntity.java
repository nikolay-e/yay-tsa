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
@Table(name = "transcode_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", foreignKey = @ForeignKey(name = "fk_transcode_jobs_session"))
    private SessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_transcode_jobs_item"))
    private ItemEntity item;

    @Column(name = "process_id", length = 255)
    private String processId;

    @Column(length = 50)
    private String codec;

    @Column(length = 20)
    private String bitrate;

    @Column(length = 50)
    private String container;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(length = 20)
    private String status = "Running";
}
