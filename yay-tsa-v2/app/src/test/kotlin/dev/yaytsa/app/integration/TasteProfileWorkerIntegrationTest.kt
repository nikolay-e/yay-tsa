package dev.yaytsa.app.integration

import dev.yaytsa.worker.ml.TasteProfileAggregator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class TasteProfileWorkerIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var aggregator: TasteProfileAggregator

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun seedEntity(
        entityType: String,
        name: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            entityType,
            name,
            name.lowercase(),
            "/taste/$id",
            name.lowercase(),
        )
        return id
    }

    private fun seedAffinity(
        userId: UUID,
        trackId: UUID,
        score: Double,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_ml.user_track_affinity " +
                "(user_id, track_id, affinity_score, play_count, updated_at) VALUES (?,?,?,1,now())",
            userId,
            trackId,
            score,
        )
    }

    @Test
    fun `rebuild writes a taste profile summarizing top artists and genres by affinity`() {
        val userId = UUID.randomUUID()
        val artistId = seedEntity("ARTIST", "Taste Artist")
        val trackId = seedEntity("TRACK", "Taste Track")
        val dislikedTrackId = seedEntity("TRACK", "Disliked Track")
        val genreId = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?,?) ON CONFLICT (name) DO NOTHING", genreId, "Tastecore")
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms, album_artist_id) VALUES (?,?,?)",
            trackId,
            100_000L,
            artistId,
        )
        jdbc.update(
            "INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) " +
                "SELECT ?, id FROM core_v2_library.genres WHERE name = 'Tastecore'",
            trackId,
        )
        seedAffinity(userId, trackId, 5.0)
        seedAffinity(userId, dislikedTrackId, -2.0)

        aggregator.rebuild()

        val row =
            jdbc.queryForMap(
                "SELECT summary_text, track_count FROM core_v2_ml.taste_profiles WHERE user_id = ?",
                userId,
            )
        val summary = row["summary_text"] as String
        assertTrue(summary.contains("Taste Artist"), "summary must name the top artist, was: $summary")
        assertTrue(summary.contains("Tastecore"), "summary must name the top genre, was: $summary")
        assertEquals(1, row["track_count"], "only positively-scored tracks count")

        // Idempotent re-run refreshes in place instead of failing on the PK.
        aggregator.rebuild()
        val count =
            jdbc.queryForObject(
                "SELECT count(*) FROM core_v2_ml.taste_profiles WHERE user_id = ?",
                Long::class.java,
                userId,
            ) ?: 0L
        assertEquals(1L, count)
    }

    @Test
    fun `users with only negative affinity get no profile`() {
        val userId = UUID.randomUUID()
        seedAffinity(userId, UUID.randomUUID(), -1.5)

        aggregator.rebuild()

        val count =
            jdbc.queryForObject(
                "SELECT count(*) FROM core_v2_ml.taste_profiles WHERE user_id = ?",
                Long::class.java,
                userId,
            ) ?: -1L
        assertEquals(0L, count)
    }
}
