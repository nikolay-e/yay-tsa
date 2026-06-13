package dev.yaytsa.adapterjellyfin

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/tracks")
class JellyfinTracksUploadController {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @PostMapping("/upload")
    fun upload(
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<Any> {
        if (file.isEmpty) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf("status" to "empty_file"))
        }
        val safeName = file.originalFilename?.substringAfterLast('/')?.ifBlank { "upload.bin" } ?: "upload.bin"
        log.info("Received upload {} ({} bytes); ingest pipeline not yet implemented", safeName, file.size)
        // TODO: wire to library scanner ingest pipeline (move into music root, trigger rescan).
        return ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(
                mapOf(
                    "status" to "upload_pending_implementation",
                    "size" to file.size,
                    "originalName" to safeName,
                ),
            )
    }
}
