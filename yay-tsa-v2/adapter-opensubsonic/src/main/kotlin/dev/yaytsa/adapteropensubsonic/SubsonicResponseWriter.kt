package dev.yaytsa.adapteropensubsonic

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

@Component
class SubsonicResponseWriter {
    private val xmlMapper =
        XmlMapper().apply {
            registerKotlinModule()
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
            configure(SerializationFeature.INDENT_OUTPUT, false)
            configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true)
        }

    private val jsonMapper =
        com.fasterxml.jackson.databind
            .ObjectMapper()
            .apply {
                registerKotlinModule()
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
            }

    fun write(
        response: SubsonicResponse,
        format: String?,
    ): ResponseEntity<String> =
        try {
            if (format == "json") {
                val json = jsonMapper.writeValueAsString(response)
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json)
            } else {
                val xml = xmlMapper.writeValueAsString(SubsonicXmlResponse.from(response.subsonicResponse))
                ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml)
            }
        } catch (_: Exception) {
            val errorResponse = error(0, "Serialization error")
            val json = jsonMapper.writeValueAsString(errorResponse)
            ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(json)
        }
}

@JacksonXmlRootElement(localName = "subsonic-response", namespace = "http://subsonic.org/restapi")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SubsonicXmlResponse(
    @field:JacksonXmlProperty(isAttribute = true) val status: String,
    @field:JacksonXmlProperty(isAttribute = true) val version: String,
    @field:JacksonXmlProperty(isAttribute = true) val type: String,
    @field:JacksonXmlProperty(isAttribute = true) val serverVersion: String,
    @field:JacksonXmlProperty(isAttribute = true) val openSubsonic: Boolean,
    val error: SubsonicError? = null,
    val artists: ArtistsWrapper? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val song: ChildElement? = null,
    val albumList2: AlbumListWrapper? = null,
    val searchResult3: SearchResult3? = null,
    val playlists: PlaylistsWrapper? = null,
    val playlist: PlaylistDetail? = null,
    val starred2: Starred2? = null,
    val musicFolders: MusicFoldersWrapper? = null,
    val genres: GenresWrapper? = null,
    val user: UserDetail? = null,
    val license: LicenseDetail? = null,
) {
    companion object {
        fun from(body: SubsonicBody) =
            SubsonicXmlResponse(
                status = body.status,
                version = body.version,
                type = body.type,
                serverVersion = body.serverVersion,
                openSubsonic = body.openSubsonic,
                error = body.error,
                artists = body.artists,
                artist = body.artist,
                album = body.album,
                song = body.song,
                albumList2 = body.albumList2,
                searchResult3 = body.searchResult3,
                playlists = body.playlists,
                playlist = body.playlist,
                starred2 = body.starred2,
                musicFolders = body.musicFolders,
                genres = body.genres,
                user = body.user,
                license = body.license,
            )
    }
}
