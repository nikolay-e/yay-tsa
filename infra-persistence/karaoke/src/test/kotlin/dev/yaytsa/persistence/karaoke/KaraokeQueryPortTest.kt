package dev.yaytsa.persistence.karaoke

import dev.yaytsa.persistence.karaoke.adapter.JpaKaraokeQueryPort
import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import dev.yaytsa.persistence.karaoke.jpa.KaraokeAssetJpaRepository
import dev.yaytsa.shared.TrackId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(JpaKaraokeQueryPort::class)
class KaraokeQueryPortTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var port: JpaKaraokeQueryPort

    @Autowired
    lateinit var assetJpa: KaraokeAssetJpaRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `getAsset returns correct data`() {
        val trackId = UUID.randomUUID()
        assetJpa.saveAndFlush(
            KaraokeAssetEntity(
                trackId = trackId,
                instrumentalPath = "/audio/instrumental.wav",
                vocalPath = "/audio/vocals.wav",
                lyricsTiming = "0:00 Hello\n0:05 World",
                readyAt = now,
            ),
        )

        val result = port.getAsset(TrackId(trackId.toString()))

        assertNotNull(result)
        assertEquals(trackId.toString(), result!!.trackId.value)
        assertEquals("/audio/instrumental.wav", result.instrumentalPath)
        assertEquals("/audio/vocals.wav", result.vocalPath)
        assertEquals("0:00 Hello\n0:05 World", result.lyricsTiming)
        assertNotNull(result.readyAt)
    }

    @Test
    fun `getAsset returns null for non-existent track`() {
        val result = port.getAsset(TrackId(UUID.randomUUID().toString()))

        assertNull(result)
    }

    @Test
    fun `getReadyTrackIds returns only tracks with non-null ready_at`() {
        val readyTrackId = UUID.randomUUID()
        val notReadyTrackId = UUID.randomUUID()

        assetJpa.saveAllAndFlush(
            listOf(
                KaraokeAssetEntity(
                    trackId = readyTrackId,
                    instrumentalPath = "/audio/inst1.wav",
                    vocalPath = "/audio/voc1.wav",
                    lyricsTiming = null,
                    readyAt = now,
                ),
                KaraokeAssetEntity(
                    trackId = notReadyTrackId,
                    instrumentalPath = "/audio/inst2.wav",
                    vocalPath = null,
                    lyricsTiming = null,
                    readyAt = null,
                ),
            ),
        )

        val result = port.getReadyTrackIds()

        assertEquals(1, result.size)
        assertEquals(readyTrackId.toString(), result.first().value)
    }
}
