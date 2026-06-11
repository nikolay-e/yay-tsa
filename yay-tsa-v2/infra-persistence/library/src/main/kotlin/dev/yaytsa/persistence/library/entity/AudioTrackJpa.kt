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
    var albumId: UUID? = null,
    @Column(name = "album_artist_id")
    var albumArtistId: UUID? = null,
    @Column(name = "track_number")
    var trackNumber: Int? = null,
    @Column(name = "disc_number")
    var discNumber: Int = 1,
    @Column(name = "duration_ms")
    var durationMs: Long? = null,
    var bitrate: Int? = null,
    @Column(name = "sample_rate")
    var sampleRate: Int? = null,
    var channels: Int? = null,
    var year: Int? = null,
    var codec: String? = null,
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
