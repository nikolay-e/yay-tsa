package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.library.port.LibraryUploadIngestPort
import dev.yaytsa.application.library.port.UploadIngestResult
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RestController
@RequestMapping("/tracks")
class JellyfinTracksUploadController(
    private val authQueries: AuthQueries,
    private val uploadIngest: LibraryUploadIngestPort,
    @Value("\${yaytsa.upload.max-bytes:209715200}") private val maxUploadBytes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        if (file.isEmpty) {
            return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Uploaded file is empty")
        }
        if (file.size > maxUploadBytes) {
            return problemDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Payload Too Large",
                "Upload of ${file.size} bytes exceeds the $maxUploadBytes byte limit",
            )
        }
        val originalName = file.originalFilename?.trim().orEmpty()
        if (originalName.isEmpty()) {
            return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Filename is required")
        }
        if (containsPathTraversal(originalName)) {
            return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Filename must be a plain name without path segments")
        }
        val extension = originalName.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            return problemDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type",
                "Extension '$extension' is not allowed; expected one of ${ALLOWED_EXTENSIONS.sorted().joinToString(", ")}",
            )
        }
        val tempFile = Files.createTempFile("yaytsa-upload-", ".$extension")
        try {
            file.inputStream.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
            return when (val result = uploadIngest.ingest(tempFile, originalName)) {
                is UploadIngestResult.Ingested -> {
                    log.info("Upload {} ingested as item {} (duplicate={})", result.relativePath, result.itemId, result.duplicate)
                    ResponseEntity.ok(
                        mapOf(
                            "Id" to result.itemId,
                            "Path" to result.relativePath,
                            "Duplicate" to result.duplicate,
                        ),
                    )
                }
                UploadIngestResult.LibraryRootUnavailable ->
                    problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Library root is not configured")
                UploadIngestResult.NotIngestable ->
                    problemDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", "File could not be ingested as playable audio")
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun containsPathTraversal(name: String): Boolean =
        name.contains('/') ||
            name.contains('\\') ||
            name.contains("..") ||
            name.startsWith(".") ||
            name.any { it.isISOControl() }

    private fun requireAdmin(): ResponseEntity<Any>? {
        val auth = SecurityContextHolder.getContext().authentication
        val callerIsAdmin =
            when {
                auth is JellyfinAuthentication -> auth.isAdmin
                auth != null && auth.isAuthenticated -> authQueries.findUser(UserId(auth.name))?.isAdmin == true
                else -> false
            }
        return if (!callerIsAdmin) problemDetail(HttpStatus.FORBIDDEN, "Forbidden", "Admin role required") else null
    }

    companion object {
        private val ALLOWED_EXTENSIONS = setOf("flac", "mp3", "ogg", "opus", "m4a", "wav")
    }
}
