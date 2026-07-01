package dev.yaytsa.persistence.ml

import dev.yaytsa.persistence.ml.adapter.JpaMlQueryPort
import dev.yaytsa.persistence.ml.entity.TasteProfileEntity
import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import dev.yaytsa.persistence.ml.entity.UserTrackAffinityEntity
import dev.yaytsa.persistence.ml.jpa.TasteProfileJpaRepository
import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import dev.yaytsa.persistence.ml.jpa.UserTrackAffinityJpaRepository
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(JpaMlQueryPort::class)
class MlQueryPortTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var port: JpaMlQueryPort

    @Autowired
    lateinit var trackFeaturesJpa: TrackFeaturesJpaRepository

    @Autowired
    lateinit var tasteProfileJpa: TasteProfileJpaRepository

    @Autowired
    lateinit var affinityJpa: UserTrackAffinityJpaRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `getTrackFeatures returns correct data`() {
        val trackId = UUID.randomUUID()
        trackFeaturesJpa.saveAndFlush(trackFeaturesEntity(trackId))

        val result = port.getTrackFeatures(TrackId(trackId.toString()))

        assertNotNull(result)
        assertEquals(trackId.toString(), result!!.trackId.value)
        assertEquals(120.0f, result.bpm)
        assertEquals("Cmaj", result.musicalKey)
        assertEquals(0.75f, result.energy)
        assertEquals(0.6f, result.valence)
        assertEquals("v1.0", result.extractorVersion)
    }

    @Test
    fun `getTrackFeatures returns null for non-existent track`() {
        val result = port.getTrackFeatures(TrackId(UUID.randomUUID().toString()))

        assertNull(result)
    }

    @Test
    fun `getTasteProfile returns correct data`() {
        val userId = UUID.randomUUID()
        tasteProfileJpa.saveAndFlush(tasteProfileEntity(userId))

        val result = port.getTasteProfile(UserId(userId.toString()))

        assertNotNull(result)
        assertEquals(userId.toString(), result!!.userId.value)
        assertEquals("{\"genre\":\"rock\"}", result.profile)
        assertEquals("Likes rock music", result.summaryText)
        assertEquals(42, result.trackCount)
    }

    @Test
    fun `getUserTrackAffinity returns correct data`() {
        val userId = UUID.randomUUID()
        val trackId = UUID.randomUUID()
        affinityJpa.saveAndFlush(affinityEntity(userId, trackId, score = 0.85))

        val result = port.getUserTrackAffinity(UserId(userId.toString()), TrackId(trackId.toString()))

        assertNotNull(result)
        assertEquals(userId.toString(), result!!.userId.value)
        assertEquals(trackId.toString(), result.trackId.value)
        assertEquals(0.85, result.affinityScore)
        assertEquals(10, result.playCount)
        assertEquals(8, result.completionCount)
        assertEquals(2, result.skipCount)
    }

    @Test
    fun `getTopAffinities returns ordered by score limited`() {
        val userId = UUID.randomUUID()
        affinityJpa.saveAllAndFlush(
            listOf(
                affinityEntity(userId, UUID.randomUUID(), score = 0.3),
                affinityEntity(userId, UUID.randomUUID(), score = 0.9),
                affinityEntity(userId, UUID.randomUUID(), score = 0.6),
            ),
        )

        val result = port.getTopAffinities(UserId(userId.toString()), limit = 2)

        assertEquals(2, result.size)
        assertEquals(0.9, result[0].affinityScore)
        assertEquals(0.6, result[1].affinityScore)
    }

    @Test
    fun `getTopAffinities decays a stale high score below a fresh lower one`() {
        val userId = UUID.randomUUID()
        val staleTrack = UUID.randomUUID()
        val freshTrack = UUID.randomUUID()
        affinityJpa.saveAllAndFlush(
            listOf(
                affinityEntity(userId, staleTrack, score = 10.0, lastSignalAt = now.minus(120, ChronoUnit.DAYS)),
                affinityEntity(userId, freshTrack, score = 2.0, lastSignalAt = now),
            ),
        )

        val result = port.getTopAffinities(UserId(userId.toString()), limit = 2)

        assertEquals(2, result.size)
        assertEquals(freshTrack.toString(), result[0].trackId.value)
        assertEquals(staleTrack.toString(), result[1].trackId.value)
    }

    private fun trackFeaturesEntity(trackId: UUID) =
        TrackFeaturesEntity(
            trackId = trackId,
            bpm = 120.0f,
            bpmConfidence = 0.95f,
            musicalKey = "Cmaj",
            keyConfidence = 0.8f,
            energy = 0.75f,
            loudnessIntegrated = -14.0f,
            loudnessRange = 8.0f,
            averageLoudness = -12.0f,
            valence = 0.6f,
            arousal = 0.7f,
            danceability = 0.65f,
            vocalInstrumental = 0.8f,
            voiceGender = "male",
            spectralComplexity = 0.5f,
            dissonance = 0.3f,
            onsetRate = 2.5f,
            introDurationSec = 15.0f,
            embeddingDiscogs = FloatArray(DISCOGS_DIM) { 0.1f },
            embeddingMusicnn = FloatArray(MUSICNN_DIM) { 0.4f },
            embeddingClap = FloatArray(CLAP_DIM) { 0.7f },
            embeddingMert = FloatArray(MERT_DIM) { 1.0f },
            extractedAt = now,
            extractorVersion = "v1.0",
        )

    private fun tasteProfileEntity(userId: UUID) =
        TasteProfileEntity(
            userId = userId,
            profile = "{\"genre\":\"rock\"}",
            summaryText = "Likes rock music",
            rebuiltAt = now,
            trackCount = 42,
            embeddingMert = FloatArray(MERT_DIM) { 0.1f },
            embeddingClap = FloatArray(CLAP_DIM) { 0.3f },
        )

    companion object {
        private const val DISCOGS_DIM = 1280
        private const val MUSICNN_DIM = 200
        private const val CLAP_DIM = 512
        private const val MERT_DIM = 768
    }

    private fun affinityEntity(
        userId: UUID,
        trackId: UUID,
        score: Double,
        lastSignalAt: Instant = now,
    ) = UserTrackAffinityEntity(
        userId = userId,
        trackId = trackId,
        affinityScore = score,
        playCount = 10,
        completionCount = 8,
        skipCount = 2,
        thumbsUpCount = 5,
        thumbsDownCount = 0,
        totalListenSec = 3600,
        lastSignalAt = lastSignalAt,
        updatedAt = now,
    )
}
