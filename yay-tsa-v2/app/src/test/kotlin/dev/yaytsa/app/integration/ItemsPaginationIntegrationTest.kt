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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class ItemsPaginationIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String

    @BeforeEach
    fun seed() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "page-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        repeat(3) {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                id,
                "ARTIST",
                "PageArtist-$it-${id.toString().take(6)}",
                "pageartist-$it",
                "pageartist",
            )
            jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", id)
        }
    }

    @Test
    fun `Artists TotalRecordCount is the DB count, not the page size`() {
        val result = get("/Artists?Limit=1", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        val total = body.get("TotalRecordCount").asInt()
        val returned = body.get("Items").size()
        assertEquals(1, returned, "Limit=1 must return one page item")
        assertTrue(total >= 3, "TotalRecordCount must reflect all artists (>=3), was $total — infinite scroll truncates")
    }

    @Test
    fun `search TotalRecordCount sums matches across types, not page size`() {
        val result = get("/Items?SearchTerm=PageArtist&Limit=1", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertTrue(body.get("TotalRecordCount").asInt() >= 3, "search count must sum all matching artists")
        assertTrue(body.get("Items").size() <= 1, "page must respect Limit=1")
    }

    @Test
    fun `oversized Limit is capped and does not error`() {
        val result = get("/Items?SearchTerm=PageArtist&Limit=100000000", token)
        assertEquals(200, result.response.status, "huge Limit must be coerced, not OOM/500")
    }
}
