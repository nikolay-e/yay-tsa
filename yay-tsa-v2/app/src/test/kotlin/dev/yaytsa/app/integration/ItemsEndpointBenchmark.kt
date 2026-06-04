package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Endpoint-level /Items benchmark: in-process total latency (controller + JPA + JSON, via MockMvc —
 * no network), SQL statements per request (Hibernate statistics), hydrated item count and response
 * size, for Albums / Songs / recently-added / Favorites / Search.
 *
 * Gated on PERF_DB_URL so it never runs in normal CI (it needs a sizeable seed). Run it against any
 * Postgres 16 (citext+pg_trgm+vector) without Docker:
 *
 *   PERF_DB_URL=jdbc:postgresql://127.0.0.1:5433/db PERF_DB_USER=perf \
 *     ./gradlew :app:test --tests '*ItemsEndpointBenchmark'
 */
@EnabledIfEnvironmentVariable(named = "PERF_DB_URL", matches = ".+")
class ItemsEndpointBenchmark : HttpIntegrationTestBase() {
    @Autowired lateinit var authUseCases: AuthUseCases

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private lateinit var token: String
    private lateinit var userId: String

    private val artists = 250
    private val albums = 500
    private val tracks = 4250
    private val favs = 500

    @BeforeEach
    fun seed() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = java.time.Instant.now()
        authUseCases.execute(
            CreateUser(uid, "bench-${userId.take(8)}", "testpassword", "Bench", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("bench"), "Bench", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        // One DO block so temp tables survive across the inserts that link tracks->albums->artists.
        jdbc.execute(
            """
            DO ${"$$"}
            DECLARE uid text := '$userId';
            BEGIN
              CREATE TEMP TABLE art(n int, id uuid) ON COMMIT DROP;
              INSERT INTO art SELECT g, gen_random_uuid() FROM generate_series(1,$artists) g;
              INSERT INTO core_v2_library.entities(id,entity_type,name,sort_name,source_path,search_text,created_at)
                SELECT id,'ARTIST','Artist '||n,'artist '||lpad(n::text,7,'0'),'/a/'||n||'_'||uid,'artist '||n,now() FROM art;
              INSERT INTO core_v2_library.artists(entity_id) SELECT id FROM art;

              CREATE TEMP TABLE alb(n int, id uuid, artist_id uuid) ON COMMIT DROP;
              INSERT INTO alb SELECT g, gen_random_uuid(), a.id FROM generate_series(1,$albums) g JOIN art a ON a.n=1+(g % $artists);
              INSERT INTO core_v2_library.entities(id,entity_type,name,sort_name,source_path,search_text,created_at)
                SELECT id,'ALBUM','Album '||n,'album '||lpad(n::text,7,'0'),'/al/'||n||'_'||uid,'album '||n,now() FROM alb;
              INSERT INTO core_v2_library.albums(entity_id,artist_id,total_tracks) SELECT id,artist_id,10 FROM alb;
              INSERT INTO core_v2_library.images(id,entity_id,image_type,path,is_primary)
                SELECT gen_random_uuid(),id,'Primary','/img/'||n||'_'||uid,true FROM alb;

              CREATE TEMP TABLE trk(n int, id uuid, album_id uuid, artist_id uuid) ON COMMIT DROP;
              INSERT INTO trk SELECT g, gen_random_uuid(), al.id, al.artist_id FROM generate_series(1,$tracks) g JOIN alb al ON al.n=1+(g % $albums);
              INSERT INTO core_v2_library.entities(id,entity_type,name,sort_name,source_path,search_text,created_at)
                SELECT id,'TRACK','Track '||n,'track '||lpad(n::text,7,'0'),'/t/'||n||'_'||uid,'track '||n,now()-((n/10)||' seconds')::interval FROM trk;
              INSERT INTO core_v2_library.audio_tracks(entity_id,album_id,album_artist_id,track_number,disc_number,duration_ms)
                SELECT id,album_id,artist_id,1+(n%12),1,180000 FROM trk;

              INSERT INTO core_v2_preferences.user_preferences(user_id,version) VALUES (uid,0) ON CONFLICT (user_id) DO NOTHING;
              INSERT INTO core_v2_preferences.favorites(user_id,track_id,favorited_at,position)
                SELECT uid, t.id::text, now()-(t.n||' minutes')::interval, t.n-1 FROM trk t WHERE t.n <= $favs;
            END ${"$$"};
            """.trimIndent(),
        )
        jdbc.execute("ANALYZE core_v2_library.entities")
        jdbc.execute("ANALYZE core_v2_library.audio_tracks")
        jdbc.execute("ANALYZE core_v2_preferences.favorites")
    }

    private data class Row(
        val name: String,
        val items: Int,
        val bytes: Int,
        val queries: Long,
        val p50ms: Double,
        val p95ms: Double,
    )

    @Test
    fun `benchmark Items endpoints`() {
        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        stats.isStatisticsEnabled = true

        fun measure(
            name: String,
            url: String,
        ): Row {
            repeat(5) { get(url, token) } // warm JIT + caches
            stats.clear()
            val res = get(url, token)
            val queries = stats.prepareStatementCount
            val bytes = res.response.contentAsByteArray.size
            val items = objectMapper.readTree(res.response.contentAsString).get("Items").size()
            val samples =
                (1..30)
                    .map {
                        val t0 = System.nanoTime()
                        get(url, token)
                        (System.nanoTime() - t0) / 1_000_000.0
                    }.sorted()
            return Row(name, items, bytes, queries, samples[samples.size / 2], samples[(samples.size * 95) / 100])
        }

        val rows =
            listOf(
                measure("Albums page1 (sort_name)", "/Items?IncludeItemTypes=MusicAlbum&Limit=50"),
                measure("Songs page1 (sort_name)", "/Items?IncludeItemTypes=Audio&Limit=50&SortBy=SortName"),
                measure("Songs recently-added", "/Items?IncludeItemTypes=Audio&Limit=50&SortBy=DateCreated&SortOrder=Descending"),
                measure("Favorites custom-order", "/Items?IsFavorite=true&Limit=50&SortBy=FavoritePosition"),
                measure("Search (name)", "/Items?SearchTerm=Track&Limit=50"),
            )

        println("==== /Items endpoint benchmark (artists=$artists albums=$albums tracks=$tracks favs=$favs) ====")
        println(String.format("%-26s %6s %9s %8s %8s %8s", "scenario", "items", "bytes", "queries", "p50ms", "p95ms"))
        rows.forEach {
            println(String.format("%-26s %6d %9d %8d %8.2f %8.2f", it.name, it.items, it.bytes, it.queries, it.p50ms, it.p95ms))
        }
        // Guard against the scheduled library scan wiping the fake-path seed mid-run: if it did, the
        // pages would be empty and the numbers meaningless. Fail loudly instead of reporting zeros.
        org.junit.jupiter.api.Assertions
            .assertTrue(rows.all { it.items > 0 }, "seed was empty during measurement — rerun")
    }
}
