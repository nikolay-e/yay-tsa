package dev.yaytsa.worker.metadata

import java.text.Normalizer

data class MetadataCandidate(
    val mbid: String,
    val title: String,
    val artistName: String?,
    val score: Int,
    val trackCount: Int? = null,
    val year: Int? = null,
)

data class LocalAlbum(
    val title: String,
    val artistName: String?,
    val trackCount: Int?,
    val year: Int?,
)

data class MatchResult(
    val candidate: MetadataCandidate,
    val distance: Double,
)

object ReleaseMatcher {
    private const val ACCEPT_DISTANCE = 0.15
    private const val MIN_GAP = 0.05
    private const val TITLE_WEIGHT = 3.0
    private const val ARTIST_WEIGHT = 3.0
    private const val TRACK_WEIGHT = 2.0

    private val variousArtistsMarkers =
        setOf("", "various artists", "various", "va", "unknown").map(::normalize).toSet()

    fun match(
        local: LocalAlbum,
        candidates: List<MetadataCandidate>,
    ): MatchResult? {
        if (candidates.isEmpty()) return null
        if (normalize(local.title).isEmpty()) return null

        val scored =
            candidates
                .map { MatchResult(it, distance(local, it)) }
                .sortedBy { it.distance }

        val best = scored.first()
        if (best.distance > ACCEPT_DISTANCE) return null

        val secondBest = scored.getOrNull(1)
        if (secondBest != null && (secondBest.distance - best.distance) < MIN_GAP) return null

        return best
    }

    fun distance(
        local: LocalAlbum,
        candidate: MetadataCandidate,
    ): Double {
        var weightedSum = 0.0
        var activeWeight = 0.0

        val titleDistance = normalizedEditDistance(local.title, candidate.title)
        weightedSum += TITLE_WEIGHT * titleDistance
        activeWeight += TITLE_WEIGHT

        if (!isVariousArtists(local.artistName) && !candidate.artistName.isNullOrBlank()) {
            val artistDistance = normalizedEditDistance(local.artistName.orEmpty(), candidate.artistName)
            weightedSum += ARTIST_WEIGHT * artistDistance
            activeWeight += ARTIST_WEIGHT
        }

        if (local.trackCount != null && candidate.trackCount != null) {
            val trackDistance = trackCountDistance(local.trackCount, candidate.trackCount)
            weightedSum += TRACK_WEIGHT * trackDistance
            activeWeight += TRACK_WEIGHT
        }

        if (activeWeight == 0.0) return 1.0
        return weightedSum / activeWeight
    }

    fun isVariousArtists(artistName: String?): Boolean = normalize(artistName.orEmpty()) in variousArtistsMarkers

    private fun trackCountDistance(
        local: Int,
        candidate: Int,
    ): Double {
        if (local == candidate) return 0.0
        val span = maxOf(local, candidate).coerceAtLeast(1)
        return (Math.abs(local - candidate).toDouble() / span).coerceAtMost(1.0)
    }

    fun normalizedEditDistance(
        left: String,
        right: String,
    ): Double {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 0.0
        return levenshtein(a, b).toDouble() / maxLength
    }

    fun normalize(value: String): String {
        var result = value.lowercase()
        result = Normalizer.normalize(result, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
        result = result.replace("&", "and")
        result = result.replace("\\b(feat\\.?|ft\\.?|featuring)\\b.*".toRegex(), " ")
        result = result.replace("\\([^)]*\\)".toRegex(), " ")
        result = result.replace("\\[[^]]*]".toRegex(), " ")
        result = result.replace("^the\\b".toRegex(), " ")
        result = result.replace("[^a-z0-9]".toRegex(), "")
        return result
    }

    fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] =
                    minOf(
                        current[j - 1] + 1,
                        previous[j] + 1,
                        previous[j - 1] + cost,
                    )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
