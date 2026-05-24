package dev.yaytsa.persistence.ml

import dev.yaytsa.persistence.ml.adapter.JpaMlQueryPort
import dev.yaytsa.persistence.ml.entity.TrackFeaturesEntity
import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import dev.yaytsa.shared.TrackId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

@Import(JpaMlQueryPort::class)
class MlSimilarityTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var port: JpaMlQueryPort

    @Autowired
    lateinit var trackFeaturesJpa: TrackFeaturesJpaRepository

    private fun mert(vararg nonZero: Pair<Int, Float>): FloatArray {
        val v = FloatArray(MERT_DIM) { 0f }
        nonZero.forEach { (i, value) -> v[i] = value }
        return v
    }

    private fun featuresWithMert(
        trackId: UUID,
        embeddingMert: FloatArray,
    ) = TrackFeaturesEntity(
        trackId = trackId,
        embeddingMert = embeddingMert,
        extractedAt = Instant.now(),
        extractorVersion = "test",
    )

    @Test
    fun `findSimilarTracks orders by cosine distance and excludes the seed`() {
        val seed = UUID.randomUUID()
        val near = UUID.randomUUID()
        val far = UUID.randomUUID()
        // seed=(1,0..), near=(1,1,0..) cos≈0.707, far=(0,1,0..) cos=0 → near is closer.
        trackFeaturesJpa.saveAndFlush(featuresWithMert(seed, mert(0 to 1f)))
        trackFeaturesJpa.saveAndFlush(featuresWithMert(near, mert(0 to 1f, 1 to 1f)))
        trackFeaturesJpa.saveAndFlush(featuresWithMert(far, mert(1 to 1f)))

        val result = port.findSimilarTracks(TrackId(seed.toString()), 10)

        assertFalse(result.contains(TrackId(seed.toString())), "seed must be excluded")
        assertTrue(result.isNotEmpty(), "similarity search returned nothing — HNSW/<=> path is dead")
        assertEquals(TrackId(near.toString()), result.first(), "nearer embedding must rank first")
    }

    @Test
    fun `findSimilarTracks falls through to CLAP when MERT embeddings are absent`() {
        val seed = UUID.randomUUID()
        val near = UUID.randomUUID()
        val far = UUID.randomUUID()

        fun clap(vararg nz: Pair<Int, Float>) = FloatArray(CLAP_DIM) { 0f }.also { v -> nz.forEach { (i, x) -> v[i] = x } }
        trackFeaturesJpa.saveAndFlush(TrackFeaturesEntity(trackId = seed, embeddingClap = clap(0 to 1f), extractedAt = Instant.now(), extractorVersion = "t"))
        trackFeaturesJpa.saveAndFlush(
            TrackFeaturesEntity(trackId = near, embeddingClap = clap(0 to 1f, 1 to 1f), extractedAt = Instant.now(), extractorVersion = "t"),
        )
        trackFeaturesJpa.saveAndFlush(TrackFeaturesEntity(trackId = far, embeddingClap = clap(1 to 1f), extractedAt = Instant.now(), extractorVersion = "t"))

        val result = port.findSimilarTracks(TrackId(seed.toString()), 10)

        assertTrue(result.isNotEmpty(), "CLAP fallback never reached — the .ifEmpty{} chain is dead")
        assertEquals(TrackId(near.toString()), result.first())
    }

    @Test
    fun `findSimilarTracks falls through to Discogs when MERT and CLAP are absent`() {
        val seed = UUID.randomUUID()
        val near = UUID.randomUUID()

        fun discogs(vararg nz: Pair<Int, Float>) = FloatArray(DISCOGS_DIM) { 0f }.also { v -> nz.forEach { (i, x) -> v[i] = x } }
        trackFeaturesJpa.saveAndFlush(
            TrackFeaturesEntity(trackId = seed, embeddingDiscogs = discogs(0 to 1f), extractedAt = Instant.now(), extractorVersion = "t"),
        )
        trackFeaturesJpa.saveAndFlush(
            TrackFeaturesEntity(trackId = near, embeddingDiscogs = discogs(0 to 1f, 1 to 1f), extractedAt = Instant.now(), extractorVersion = "t"),
        )

        val result = port.findSimilarTracks(TrackId(seed.toString()), 10)

        assertTrue(result.contains(TrackId(near.toString())), "Discogs fallback never reached")
    }

    @Test
    fun `findSimilarTracks returns empty when seed has no embeddings`() {
        val seed = UUID.randomUUID()
        trackFeaturesJpa.saveAndFlush(TrackFeaturesEntity(trackId = seed, extractedAt = Instant.now(), extractorVersion = "test"))

        val result = port.findSimilarTracks(TrackId(seed.toString()), 10)

        assertTrue(result.isEmpty())
    }

    companion object {
        private const val MERT_DIM = 768
        private const val CLAP_DIM = 512
        private const val DISCOGS_DIM = 1280
    }
}
