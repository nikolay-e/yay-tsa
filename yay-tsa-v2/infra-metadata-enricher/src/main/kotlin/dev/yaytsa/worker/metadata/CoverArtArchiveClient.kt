package dev.yaytsa.worker.metadata

import java.net.http.HttpClient
import java.time.Duration

data class CoverArt(
    val bytes: ByteArray,
    val extension: String,
)

class CoverArtArchiveClient(
    private val baseUrl: String,
    userAgent: String,
    rateLimiter: RateLimiter,
) {
    private val http =
        ProviderHttp(
            "Cover Art Archive",
            userAgent,
            rateLimiter,
            ProviderHttp.TransientFailurePolicy.THROW_UNAVAILABLE,
            HttpClient.Version.HTTP_1_1,
        )

    fun fetchReleaseGroupFront(releaseGroupMbid: String): CoverArt? =
        http.getImage("$baseUrl/release-group/$releaseGroupMbid/front-500", Duration.ofSeconds(15))
            ?: http.getImage("$baseUrl/release-group/$releaseGroupMbid/front", Duration.ofSeconds(15))
}
