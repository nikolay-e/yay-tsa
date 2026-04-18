package dev.yaytsa.app

import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import javax.sql.DataSource

@Configuration
class FlywayConfig(
    private val dataSource: DataSource,
) {
    private fun flywayForSchema(
        schema: String,
        location: String,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .locations(location)
            .createSchemas(true)
            .load()

    @Bean(initMethod = "migrate")
    fun flywayShared(): Flyway = flywayForSchema("core_v2_shared", "classpath:db/shared")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayAuth(): Flyway = flywayForSchema("core_v2_auth", "classpath:db/auth")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayPlayback(): Flyway = flywayForSchema("core_v2_playback", "classpath:db/playback")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayPlaylists(): Flyway = flywayForSchema("core_v2_playlists", "classpath:db/playlists")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayPreferences(): Flyway = flywayForSchema("core_v2_preferences", "classpath:db/preferences")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayAdaptive(): Flyway = flywayForSchema("core_v2_adaptive", "classpath:db/adaptive")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayLibrary(): Flyway = flywayForSchema("core_v2_library", "classpath:db/library")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayMl(): Flyway = flywayForSchema("core_v2_ml", "classpath:db/ml")

    @Bean(initMethod = "migrate")
    @DependsOn("flywayShared")
    fun flywayKaraoke(): Flyway = flywayForSchema("core_v2_karaoke", "classpath:db/karaoke")
}
