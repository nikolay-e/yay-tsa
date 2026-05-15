package dev.yaytsa.persistence.ml

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
    fun `track_features embedding columns are pgvector type`() {
        val columns = getColumnTypes("core_v2_ml", "track_features")
        assertEquals("USER-DEFINED", columns["embedding_discogs"], "embedding_discogs should be pgvector (USER-DEFINED)")
        assertEquals("USER-DEFINED", columns["embedding_musicnn"], "embedding_musicnn should be pgvector (USER-DEFINED)")
        assertEquals("USER-DEFINED", columns["embedding_clap"], "embedding_clap should be pgvector (USER-DEFINED)")
        assertEquals("USER-DEFINED", columns["embedding_mert"], "embedding_mert should be pgvector (USER-DEFINED)")
    }

    @Test
    fun `taste_profiles embedding columns are pgvector type`() {
        val columns = getColumnTypes("core_v2_ml", "taste_profiles")
        assertEquals("USER-DEFINED", columns["embedding_mert"], "embedding_mert should be pgvector (USER-DEFINED)")
        assertEquals("USER-DEFINED", columns["embedding_clap"], "embedding_clap should be pgvector (USER-DEFINED)")
    }

    @Test
    fun `track_features scalar columns have correct types`() {
        val columns = getColumnTypes("core_v2_ml", "track_features")
        assertEquals("real", columns["bpm"])
        assertEquals("uuid", columns["track_id"])
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
