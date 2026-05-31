package dev.yaytsa.app.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

// MPD runs in the shared context (enabled on mpdPort in HttpIntegrationTestBase), so this connects
// over TCP without a second Spring context — avoiding the singleton JCache CacheManager collision.
class MpdProtocolIntegrationTest : HttpIntegrationTestBase() {
    private fun runCommands(lines: List<String>): String {
        Socket("127.0.0.1", mpdPort).use { socket ->
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            reader.readLine() // banner: "OK MPD x.y.z"
            lines.forEach { writer.println(it) }
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
                if (line == "OK" || line.startsWith("ACK")) break
            }
            return sb.toString()
        }
    }

    @Test
    fun `command_list_ok_begin emits a list_OK after each sub-command and one terminating OK`() {
        val response = runCommands(listOf("command_list_ok_begin", "status", "status", "command_list_end"))

        val listOkCount = response.lines().count { it == "list_OK" }
        assertTrue(listOkCount == 2, "expected one list_OK per sub-command, got $listOkCount in:\n$response")
        assertTrue(response.trimEnd().endsWith("OK"), "batch must end with a single terminating OK")
        assertFalse(
            response.trim() == "OK",
            "a successful batch must carry each sub-command's payload, not a bare OK (the pre-fix bug)",
        )
    }

    @Test
    fun `plain command_list_begin concatenates payloads with no list_OK separators`() {
        val response = runCommands(listOf("command_list_begin", "status", "command_list_end"))
        assertFalse(response.lines().any { it == "list_OK" }, "plain command list must not emit list_OK")
        assertTrue(response.trimEnd().endsWith("OK"))
        assertTrue(response.contains("state:") || response.contains("volume:"), "status payload must be present, was:\n$response")
    }
}
