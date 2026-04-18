package dev.yaytsa.persistence.library.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "audio_tracks", schema = "core_v2_library")
class AudioTrackJpa(
    @Id
    @Column(name = "entity_id")
    val entityId: UUID = UUID.randomUUID(),
    @Column(name = "album_id")
    val albumId: UUID? = null,
    @Column(name = "album_artist_id")
    val albumArtistId: UUID? = null,
    @Column(name = "track_number")
    val trackNumber: Int? = null,
    @Column(name = "disc_number")
    val discNumber: Int = 1,
    @Column(name = "duration_ms")
    val durationMs: Long? = null,
    val bitrate: Int? = null,
    @Column(name = "sample_rate")
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val year: Int? = null,
    val codec: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,
    val fingerprint: String? = null,
    @Column(name = "fingerprint_duration")
    val fingerprintDuration: Double? = null,
    @Column(name = "fingerprint_sample_rate")
    val fingerprintSampleRate: Int? = null,
    @Column(name = "replaygain_track_gain")
    val replaygainTrackGain: BigDecimal? = null,
    @Column(name = "replaygain_album_gain")
    val replaygainAlbumGain: BigDecimal? = null,
    @Column(name = "replaygain_track_peak")
    val replaygainTrackPeak: BigDecimal? = null,
)
