package dev.yaytsa.persistence.playback

import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class PlayHistoryRecencyTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repo: PlayHistoryJpaRepository

    private fun row(
        user: String,
        item: String,
        recordedAt: Instant,
    ) = PlayHistoryEntity(
        id = UUID.randomUUID(),
        userId = user,
        itemId = item,
        startedAt = recordedAt,
        durationMs = 1000,
        playedMs = 1000,
        completed = true,
        scrobbled = false,
        skipped = false,
        recordedAt = recordedAt,
    )

    @Test
    fun `recently played item ids are distinct and ordered by most recent play`() {
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        // track-A has two plays; its latest (t0+120) is the most recent overall.
        repo.saveAll(
            listOf(
                row("u1", "track-A", t0),
                row("u1", "track-B", t0.plusSeconds(60)),
                row("u1", "track-C", t0.plusSeconds(30)),
                row("u1", "track-A", t0.plusSeconds(120)),
                row("u2", "track-Z", t0.plusSeconds(999)), // other user — must not leak in
            ),
        )

        val ids = repo.findRecentlyPlayedItemIdsByUser("u1", 10)

        assertEquals(listOf("track-A", "track-B", "track-C"), ids)
    }

    @Test
    fun `limit caps the number of distinct tracks`() {
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        repo.saveAll(
            listOf(
                row("u1", "track-A", t0.plusSeconds(3)),
                row("u1", "track-B", t0.plusSeconds(2)),
                row("u1", "track-C", t0.plusSeconds(1)),
            ),
        )

        val ids = repo.findRecentlyPlayedItemIdsByUser("u1", 2)

        assertEquals(listOf("track-A", "track-B"), ids)
    }

    @Test
    fun `no history yields empty`() {
        assertEquals(emptyList(), repo.findRecentlyPlayedItemIdsByUser("nobody", 10))
    }
}
