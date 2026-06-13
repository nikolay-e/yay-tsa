package dev.yaytsa.adapterjellyfin

import dev.yaytsa.shared.Hashing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.path.exists

/**
 * Resizes/re-encodes cover art on demand and caches the result on disk.
 *
 * The contract the API always advertised (maxWidth/maxHeight/quality) is now honoured:
 *  - dimensions are clamped to [MIN_DIM, MAX_DIM] and quality to [MIN_Q, MAX_Q]; bad input is
 *    clamped, never a 500;
 *  - aspect ratio is preserved and images are never upscaled;
 *  - no size params -> the original file is served unchanged (back-compat);
 *  - WebP is produced when the client sends `Accept: image/webp` AND a `cwebp` binary is present
 *    (musl-native libwebp-tools on the Alpine runtime); otherwise it falls back to JPEG. A failed
 *    encode degrades to the original rather than erroring.
 *
 * Cache key = sha256(absPath | mtime | size | targetW | targetH | quality | format), so a changed
 * source (mtime/size) naturally invalidates, and an identical request is a pure cache read.
 */
@Service
class ThumbnailService(
    @Value("\${yaytsa.image.cache-dir:#{null}}") cacheDir: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheRoot: Path =
        (cacheDir?.let { Path.of(it) } ?: Path.of(System.getProperty("java.io.tmpdir"), "yaytsa-thumbnails"))
            .also { runCatching { Files.createDirectories(it) } }
    private val cwebp: String? = findCwebp()

    data class Rendered(
        val file: Path,
        val contentType: String,
        val etag: String,
    )

    fun render(
        source: Path,
        maxWidth: Int?,
        maxHeight: Int?,
        quality: Int?,
        acceptWebp: Boolean,
    ): Rendered {
        val mtime = Files.getLastModifiedTime(source).toMillis()
        val size = Files.size(source)

        // No size requested -> original, untouched (existing contract). Format is left as-is.
        if (maxWidth == null && maxHeight == null) {
            return Rendered(source, contentTypeForFile(source), etag(source, mtime, size, 0, 0, "orig"))
        }

        val w = maxWidth?.coerceIn(MIN_DIM, MAX_DIM)
        val h = maxHeight?.coerceIn(MIN_DIM, MAX_DIM)
        val q = (quality ?: DEFAULT_Q).coerceIn(MIN_Q, MAX_Q)
        val useWebp = acceptWebp && cwebp != null
        val format = if (useWebp) "webp" else "jpeg"

        return try {
            val src = ImageIO.read(source.toFile()) ?: return original(source, mtime, size)
            val (tw, th) = targetDimensions(src.width, src.height, w, h)
            val key = cacheKey(source, mtime, size, tw, th, q, format)
            val cacheFile = cacheRoot.resolve("$key.$format")
            val contentType = if (format == "webp") "image/webp" else "image/jpeg"
            if (cacheFile.exists()) return Rendered(cacheFile, contentType, "\"$key\"")

            val scaled = scale(src, tw, th)
            if (useWebp) {
                encodeWebp(scaled, q, cacheFile)
            } else {
                Files.write(cacheFile, encodeJpeg(scaled, q))
            }
            Rendered(cacheFile, contentType, "\"$key\"")
        } catch (e: Exception) {
            log.warn("Thumbnail render failed for {} ({}x{} q{}): {}", source, w, h, q, e.toString())
            original(source, mtime, size)
        }
    }

    private fun original(
        source: Path,
        mtime: Long,
        size: Long,
    ) = Rendered(source, contentTypeForFile(source), etag(source, mtime, size, 0, 0, "orig"))

    /** Preserve aspect ratio, never upscale. */
    private fun targetDimensions(
        sw: Int,
        sh: Int,
        maxW: Int?,
        maxH: Int?,
    ): Pair<Int, Int> {
        val boundW = maxW ?: Int.MAX_VALUE
        val boundH = maxH ?: Int.MAX_VALUE
        val scale = minOf(boundW.toDouble() / sw, boundH.toDouble() / sh, 1.0) // <=1 => no upscale
        val tw = maxOf(1, Math.round(sw * scale).toInt())
        val th = maxOf(1, Math.round(sh * scale).toInt())
        return tw to th
    }

    private fun scale(
        src: BufferedImage,
        tw: Int,
        th: Int,
    ): BufferedImage {
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, tw, th, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encodeJpeg(
        img: BufferedImage,
        quality: Int,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return bos.toByteArray()
    }

    private fun encodeWebp(
        img: BufferedImage,
        quality: Int,
        dest: Path,
    ) {
        val tmp = Files.createTempFile(cacheRoot, "src", ".png")
        try {
            Files.write(tmp, ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray())
            val proc =
                ProcessBuilder(cwebp!!, "-quiet", "-q", quality.toString(), tmp.toString(), "-o", dest.toString())
                    .redirectErrorStream(true)
                    .start()
            if (!proc.waitFor(20, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                proc.destroyForcibly()
                throw IllegalStateException("cwebp failed (exit ${runCatching { proc.exitValue() }.getOrNull()})")
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun cacheKey(
        source: Path,
        mtime: Long,
        size: Long,
        tw: Int,
        th: Int,
        q: Int,
        fmt: String,
    ): String = Hashing.sha256Hex("${source.toAbsolutePath()}|$mtime|$size|$tw|$th|$q|$fmt")

    private fun etag(
        source: Path,
        mtime: Long,
        size: Long,
        tw: Int,
        th: Int,
        fmt: String,
    ): String = "\"${cacheKey(source, mtime, size, tw, th, 0, fmt)}\""

    private fun contentTypeForFile(path: Path): String =
        when (path.toString().substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

    private fun findCwebp(): String? =
        listOf("/usr/bin/cwebp", "/usr/local/bin/cwebp", "cwebp")
            .firstOrNull { candidate ->
                runCatching {
                    val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                    p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                }.getOrDefault(false)
            }.also { if (it == null) log.info("cwebp not found; WebP cover encoding disabled (serving JPEG)") }

    companion object {
        const val MIN_DIM = 32
        const val MAX_DIM = 1200
        const val MIN_Q = 40
        const val MAX_Q = 95
        const val DEFAULT_Q = 80
    }
}
