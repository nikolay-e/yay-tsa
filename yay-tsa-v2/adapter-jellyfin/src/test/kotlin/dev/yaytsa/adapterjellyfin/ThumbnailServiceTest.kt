package dev.yaytsa.adapterjellyfin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO

class ThumbnailServiceTest :
    StringSpec({
        fun service(): ThumbnailService = ThumbnailService(Files.createTempDirectory("thumbtest").toString())

        fun source(
            w: Int,
            h: Int,
        ): Path {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            for (x in 0 until w step 8) {
                g.color = Color(Math.floorMod(x, 256), Math.floorMod(x * 2, 256), Math.floorMod(h - x, 256))
                g.fillRect(x, 0, 8, h)
            }
            g.dispose()
            val f = Files.createTempFile("src", ".png")
            ImageIO.write(img, "png", f.toFile())
            return f
        }

        fun dims(p: Path): Pair<Int, Int> = ImageIO.read(p.toFile()).let { it.width to it.height }

        "maxWidth limits width and preserves aspect ratio" {
            val src = source(1000, 800)
            val r = service().render(src, 160, null, null, acceptWebp = false)
            r.contentType shouldBe "image/jpeg"
            val (w, h) = dims(r.file)
            w shouldBeLessThanOrEqual 160
            // 160 * 800/1000 = 128, allow rounding
            (h in 126..130).shouldBeTrue()
        }

        "does not upscale a source smaller than the requested size" {
            val src = source(100, 80)
            val (w, _) = dims(service().render(src, 160, 160, null, acceptWebp = false).file)
            w shouldBeLessThanOrEqual 100
        }

        "lower quality produces fewer bytes" {
            val src = source(800, 800)
            val svc = service()
            val low = Files.size(svc.render(src, 400, null, 40, acceptWebp = false).file)
            val high = Files.size(svc.render(src, 400, null, 92, acceptWebp = false).file)
            low shouldBeLessThan high
        }

        "no size params returns the original file untouched" {
            val src = source(1000, 1000)
            val r = service().render(src, null, null, null, acceptWebp = false)
            r.file shouldBe src
            r.contentType shouldBe "image/png"
        }

        "absurd params are clamped and never throw" {
            val src = source(1000, 1000)
            val r = service().render(src, 999_999, -5, 999, acceptWebp = false)
            val (w, h) = dims(r.file)
            w shouldBeLessThanOrEqual ThumbnailService.MAX_DIM
            h shouldBeLessThanOrEqual ThumbnailService.MAX_DIM
        }

        "identical request hits the cache and is not re-rendered" {
            val src = source(600, 600)
            val svc = service()
            val first = svc.render(src, 160, null, 80, acceptWebp = false).file
            // Overwrite the cached variant with a sentinel; a cache hit must NOT regenerate it.
            Files.write(first, byteArrayOf(1, 2, 3))
            val second = svc.render(src, 160, null, 80, acceptWebp = false).file
            second shouldBe first
            Files.readAllBytes(second) shouldBe byteArrayOf(1, 2, 3)
        }

        "changing the source mtime invalidates the cache" {
            val src = source(600, 600)
            val svc = service()
            val a = svc.render(src, 160, null, 80, acceptWebp = false)
            Files.setLastModifiedTime(src, FileTime.fromMillis(Files.getLastModifiedTime(src).toMillis() + 60_000))
            val b = svc.render(src, 160, null, 80, acceptWebp = false)
            b.file shouldNotBe a.file
            b.etag shouldNotBe a.etag
        }

        "Accept webp yields WebP when cwebp is available, JPEG otherwise" {
            val src = source(1000, 1000)
            val r = service().render(src, 160, null, 80, acceptWebp = true)
            if (r.contentType == "image/webp") {
                val bytes = Files.readAllBytes(r.file)
                String(bytes, 0, 4) shouldBe "RIFF"
                String(bytes, 8, 4) shouldBe "WEBP"
            } else {
                r.contentType shouldBe "image/jpeg"
            }
        }
    })
