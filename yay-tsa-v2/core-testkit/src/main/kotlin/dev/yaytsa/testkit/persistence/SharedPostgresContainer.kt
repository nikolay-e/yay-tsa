package dev.yaytsa.testkit.persistence

import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object SharedPostgresContainer {
    private const val IMAGE = "pgvector/pgvector:pg16"
    private const val DB_NAME = "yaytsa_test"
    private const val USERNAME = "test"
    private const val PASSWORD = "test"

    val instance: PostgreSQLContainer<*> by lazy {
        enableTestcontainersReuse()
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withReuse(true)
            .also { container ->
                container.start()
                container.createConnection("").use { connection ->
                    connection.createStatement().use { statement ->
                        // Testcontainers reuse (withReuse) shares one Postgres across the
                        // parallel per-module Gradle test JVMs. `CREATE EXTENSION IF NOT
                        // EXISTS` is NOT concurrency-safe: two sessions can both pass the
                        // existence check and both INSERT, one then failing with "duplicate
                        // key value violates unique constraint pg_extension_name_index".
                        // A session advisory lock serializes the whole block across JVMs.
                        // Every extension any module's Flyway needs is created here first, so
                        // those later IF NOT EXISTS calls are pure no-ops and never INSERT.
                        statement.execute("SELECT pg_advisory_lock(742042)")
                        try {
                            statement.execute("CREATE EXTENSION IF NOT EXISTS citext")
                            statement.execute("CREATE EXTENSION IF NOT EXISTS vector")
                            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                            statement.execute("CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public")
                        } finally {
                            statement.execute("SELECT pg_advisory_unlock(742042)")
                        }
                    }
                }
            }
    }

    val jdbcUrlWithUnspecifiedStringType: String
        get() = instance.jdbcUrl + "&stringtype=unspecified"

    private fun enableTestcontainersReuse() {
        val home = System.getProperty("user.home") ?: return
        val propsFile = Path.of(home, ".testcontainers.properties")
        val props = Properties()
        if (Files.exists(propsFile)) {
            Files.newInputStream(propsFile).use { props.load(it) }
        }
        if (props.getProperty("testcontainers.reuse.enable") == "true") return
        props.setProperty("testcontainers.reuse.enable", "true")
        Files.newOutputStream(propsFile).use { props.store(it, "Modified by yaytsa core-testkit") }
    }
}
