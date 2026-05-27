package dev.yaytsa.worker.karaoke

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SeparationResult(
    val instrumentalPath: String,
    val vocalPath: String,
    val processingTimeMs: Long,
)

@Component
class SeparatorClient {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun separate(
        baseUrl: String,
        inputPath: String,
        trackId: String,
    ): SeparationResult {
        val payload = objectMapper.writeValueAsString(mapOf("inputPath" to inputPath, "trackId" to trackId))
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/separate"))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Separator returned ${response.statusCode()}: ${response.body().take(500)}"
        }
        val json = objectMapper.readTree(response.body())
        return SeparationResult(
            instrumentalPath = json.get("instrumental_path").asText(),
            vocalPath = json.get("vocal_path").asText(),
            processingTimeMs = json.get("processing_time_ms").asLong(),
        )
    }
}
