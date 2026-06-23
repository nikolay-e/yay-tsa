package dev.yaytsa.persistence.adaptive

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource
import kotlin.test.assertEquals

@Import(SchemaDriftTest.TestConfig::class)
class SchemaDriftTest : AbstractPersistenceTest() {
    @Configuration
    class TestConfig {
        @Bean
        fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
    }

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `listening_sessions mood_tags and seed_genres are text arrays`() {
        val columns = getColumnTypes("core_v2_adaptive", "listening_sessions")
        assertEquals("ARRAY", columns["mood_tags"], "mood_tags should be ARRAY (text[])")
        assertEquals("ARRAY", columns["seed_genres"], "seed_genres should be ARRAY (text[])")
    }

    @Test
    fun `listening_sessions state is varchar`() {
        val columns = getColumnTypes("core_v2_adaptive", "listening_sessions")
        assertEquals("character varying", columns["state"], "state should be VARCHAR")
    }

    @Test
    fun `listening_sessions has version column`() {
        val columns = getColumnTypes("core_v2_adaptive", "listening_sessions")
        assertEquals("bigint", columns["version"], "version should be BIGINT")
    }

    @Test
    fun `playback_signals context is text`() {
        val columns = getColumnTypes("core_v2_adaptive", "playback_signals")
        assertEquals("text", columns["context"], "context should be TEXT (matches PlaybackSignalEntity)")
    }

    @Test
    fun `llm_decisions json columns are text`() {
        val columns = getColumnTypes("core_v2_adaptive", "llm_decisions")
        assertEquals("text", columns["intent"], "intent should be TEXT (matches LlmDecisionEntity)")
        assertEquals("text", columns["edits"], "edits should be TEXT (matches LlmDecisionEntity)")
        assertEquals("text", columns["validation_details"], "validation_details should be TEXT")
    }

    private fun getColumnTypes(
        schema: String,
        table: String,
    ): Map<String, String> =
        jdbc
            .query(
                """
            SELECT column_name,
                   CASE WHEN data_type = 'ARRAY' THEN 'ARRAY' ELSE data_type END as data_type
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            """,
                { rs, _ -> rs.getString("column_name") to rs.getString("data_type") },
                schema,
                table,
            ).toMap()
}
