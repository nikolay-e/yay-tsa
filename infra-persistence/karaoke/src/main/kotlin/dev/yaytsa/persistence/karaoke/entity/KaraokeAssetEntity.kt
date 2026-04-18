package dev.yaytsa.persistence.karaoke.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "assets", schema = "core_v2_karaoke")
class KaraokeAssetEntity(
    @Id
    @Column(name = "track_id")
    val trackId: UUID = UUID.randomUUID(),
    @Column(name = "instrumental_path")
    val instrumentalPath: String? = null,
    @Column(name = "vocal_path")
    val vocalPath: String? = null,
    @Column(name = "lyrics_timing")
    val lyricsTiming: String? = null,
    @Column(name = "ready_at")
    val readyAt: Instant? = null,
)
