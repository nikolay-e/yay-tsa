package dev.yaytsa.app.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class GenreRelationsIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun seedGenre(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?,?) ON CONFLICT (name) DO NOTHING", id, name)
        return jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, name)!!
    }

    private fun rebuildRelations() =
        jdbc.update(
            """
            INSERT INTO core_v2_library.genre_relations (child_id, parent_id)
            SELECT c.id, p.id
            FROM core_v2_library.genres c
            JOIN core_v2_library.genres p
              ON length(c.name) > length(p.name) + 1
             AND right(lower(c.name), length(p.name) + 1) = ' ' || lower(p.name)
            ON CONFLICT DO NOTHING
            """,
        )

    private fun edgeExists(
        child: UUID,
        parent: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM core_v2_library.genre_relations WHERE child_id=? AND parent_id=?)",
            Boolean::class.java,
            child,
            parent,
        )!!

    @Test
    fun `genre relations derive nested subset edges from names, not from a hardcoded taxonomy`() {
        val metal = seedGenre("Metal")
        val deathMetal = seedGenre("Death Metal")
        val melodicDeathMetal = seedGenre("Melodic Death Metal")
        val powerMetal = seedGenre("Power Metal")
        val rock = seedGenre("Rock")

        rebuildRelations()

        // Direct and transitive ancestry both captured (overlapping/nested sets).
        assertTrue(edgeExists(powerMetal, metal), "Power Metal ⊂ Metal")
        assertTrue(edgeExists(deathMetal, metal), "Death Metal ⊂ Metal")
        assertTrue(edgeExists(melodicDeathMetal, deathMetal), "Melodic Death Metal ⊂ Death Metal")
        assertTrue(edgeExists(melodicDeathMetal, metal), "Melodic Death Metal ⊂ Metal (transitive)")
        // No spurious cross-family edge, and no self/backwards edge.
        assertFalse(edgeExists(rock, metal), "Rock is not a subgenre of Metal")
        assertFalse(edgeExists(metal, powerMetal), "parent is not a child of its descendant")
    }
}
