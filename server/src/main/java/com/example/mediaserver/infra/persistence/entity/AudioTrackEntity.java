package com.example.mediaserver.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "audio_tracks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AudioTrackEntity {

    @Id
    @Column(name = "item_id")
    private java.util.UUID itemId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_audio_tracks_item"))
    private ItemEntity item;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "album_id", foreignKey = @ForeignKey(name = "fk_audio_tracks_album"))
    private ItemEntity album;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "album_artist_id", foreignKey = @ForeignKey(name = "fk_audio_tracks_album_artist"))
    private ItemEntity albumArtist;

    @Column(name = "track_number")
    private Integer trackNumber;

    @Column(name = "disc_number")
    private Integer discNumber = 1;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column
    private Integer bitrate;

    @Column(name = "sample_rate")
    private Integer sampleRate;

    @Column
    private Integer channels;

    @Column
    private Integer year;

    @Column(length = 50)
    private String codec;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(columnDefinition = "TEXT")
    private String lyrics;
}
