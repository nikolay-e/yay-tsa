package dev.yaytsa.adapteropensubsonic

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rest")
class SubsonicSystemController(
    private val responseWriter: SubsonicResponseWriter,
) {
    @GetMapping("/ping", "/ping.view")
    fun ping(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok(), f)

    @GetMapping("/getLicense", "/getLicense.view")
    fun getLicense(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(license = LicenseDetail(valid = true)) }, f)

    @GetMapping("/getOpenSubsonicExtensions", "/getOpenSubsonicExtensions.view")
    fun getExtensions(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(openSubsonicExtensions = SupportedExtensions.all) }, f)

    @GetMapping("/getMusicFolders", "/getMusicFolders.view")
    fun getMusicFolders(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(musicFolders = MusicFoldersWrapper(listOf(MusicFolderElement("1", "Music")))) }, f)

    @RequestMapping("/**")
    fun unknownEndpoint(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(error(70, "Endpoint not found"), f)
}
