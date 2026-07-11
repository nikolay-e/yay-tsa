package dev.yaytsa.app.integration

import dev.yaytsa.application.shared.port.RemoteCommandPort
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class OutboxPayloadIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var remoteCommandPort: RemoteCommandPort

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `device-command payload with many track ids persists — outbox payload is TEXT, not varchar(255)`() {
        // A SET_QUEUE (radio seed) or batch ENQUEUE carries ~20 track ids; serialized to JSON that
        // payload is well over 255 chars. While the column was varchar(255) the insert failed with
        // SQLState 22001 ("value too long"), surfaced as an opaque 400 and broke radio, batch
        // add_to_queue, and cross-device queue transfer. This drives the real publish path and
        // asserts the full payload round-trips.
        val trackIds = (1..20).map { UUID.randomUUID().toString() }
        val marker = UUID.randomUUID().toString()
        remoteCommandPort.publish(
            userId = UUID.randomUUID().toString(),
            targetDeviceId = "web-${UUID.randomUUID().toString().take(8)}",
            command = "SET_QUEUE",
            params = mapOf("trackIds" to trackIds, "marker" to marker),
        )
        val payload =
            jdbc.queryForObject(
                "SELECT payload FROM core_v2_shared.outbox WHERE payload LIKE ?",
                String::class.java,
                "%$marker%",
            )
        assertTrue(
            (payload?.length ?: 0) > 255,
            "payload must exceed the old varchar(255) bound to prove the column is TEXT, was ${payload?.length}",
        )
        trackIds.forEach { id ->
            assertTrue(payload!!.contains(id), "every track id must survive in the persisted payload")
        }
    }
}
