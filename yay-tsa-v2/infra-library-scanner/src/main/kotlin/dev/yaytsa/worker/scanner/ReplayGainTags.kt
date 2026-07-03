package dev.yaytsa.worker.scanner

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.wav.WavTag
import java.math.BigDecimal
import java.math.RoundingMode

data class ReplayGainInfo(
    val trackGain: BigDecimal? = null,
    val albumGain: BigDecimal? = null,
    val trackPeak: BigDecimal? = null,
)

object ReplayGainTags {
    private val signedDecimal = Regex("[-+]?\\d+(?:[.,]\\d+)?")
    private val maxGainDb = BigDecimal(60)
    private val maxPeak = BigDecimal(100)
    private val r128ToReplayGainOffsetDb = BigDecimal(5)
    private val r128Q78Divisor = BigDecimal(256)

    fun read(tag: Tag?): ReplayGainInfo {
        if (tag == null) return ReplayGainInfo()
        return ReplayGainInfo(
            trackGain =
                parseGainDb(firstValue(tag, "REPLAYGAIN_TRACK_GAIN"))
                    ?: parseR128GainDb(firstValue(tag, "R128_TRACK_GAIN")),
            albumGain =
                parseGainDb(firstValue(tag, "REPLAYGAIN_ALBUM_GAIN"))
                    ?: parseR128GainDb(firstValue(tag, "R128_ALBUM_GAIN")),
            trackPeak = parsePeak(firstValue(tag, "REPLAYGAIN_TRACK_PEAK")),
        )
    }

    private fun firstValue(
        tag: Tag,
        name: String,
    ): String? =
        when (tag) {
            is AbstractID3v2Tag -> txxxValue(tag, name)
            is WavTag -> tag.getID3Tag()?.let { txxxValue(it, name) }
            else -> genericValue(tag, name)
        }?.trim()?.takeIf { it.isNotEmpty() }

    private fun genericValue(
        tag: Tag,
        name: String,
    ): String? =
        sequenceOf(
            name,
            name.lowercase(),
            "----:com.apple.iTunes:$name",
            "----:com.apple.iTunes:${name.lowercase()}",
        ).mapNotNull { key ->
            runCatching { tag.getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }
        }.firstOrNull()

    private fun txxxValue(
        tag: AbstractID3v2Tag,
        name: String,
    ): String? =
        runCatching { tag.getFields("TXXX") }
            .getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodyTXXX }
            .firstOrNull { it.description.equals(name, ignoreCase = true) }
            ?.textWithoutTrailingNulls

    private fun parseGainDb(raw: String?): BigDecimal? =
        parseNumber(raw)
            ?.takeIf { it.abs() <= maxGainDb }
            ?.setScale(4, RoundingMode.HALF_UP)

    private fun parseR128GainDb(raw: String?): BigDecimal? =
        raw
            ?.trim()
            ?.toLongOrNull()
            ?.let { BigDecimal(it).divide(r128Q78Divisor, 4, RoundingMode.HALF_UP) + r128ToReplayGainOffsetDb }
            ?.takeIf { it.abs() <= maxGainDb }
            ?.setScale(4, RoundingMode.HALF_UP)

    private fun parsePeak(raw: String?): BigDecimal? =
        parseNumber(raw)
            ?.takeIf { it.signum() >= 0 && it < maxPeak }
            ?.setScale(6, RoundingMode.HALF_UP)

    private fun parseNumber(raw: String?): BigDecimal? {
        if (raw == null) return null
        return signedDecimal
            .find(raw)
            ?.value
            ?.replace(',', '.')
            ?.toBigDecimalOrNull()
    }
}
