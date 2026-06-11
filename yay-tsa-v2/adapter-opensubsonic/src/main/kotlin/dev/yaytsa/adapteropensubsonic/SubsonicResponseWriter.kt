package dev.yaytsa.adapteropensubsonic

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.dataformat.xml.JacksonXmlAnnotationIntrospector
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

data class RenderedSubsonicResponse(
    val contentType: String,
    val body: String,
)

private class ScalarsAsAttributesIntrospector : JacksonXmlAnnotationIntrospector(false) {
    override fun isOutputAsAttribute(
        config: MapperConfig<*>,
        ann: Annotated,
    ): Boolean? {
        super.isOutputAsAttribute(config, ann)?.let { return it }
        val type = ann.rawType ?: return null
        val isScalar =
            type.isPrimitive ||
                type == String::class.java ||
                Number::class.java.isAssignableFrom(type) ||
                type == java.lang.Boolean::class.java ||
                type == java.lang.Character::class.java
        return if (isScalar) true else null
    }
}

@JacksonXmlRootElement(localName = "subsonic-response", namespace = "http://subsonic.org/restapi")
private abstract class SubsonicXmlRootMixin

@Component
class SubsonicResponseWriter {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private val xmlMapper =
        XmlMapper().apply {
            setAnnotationIntrospector(ScalarsAsAttributesIntrospector())
            registerKotlinModule()
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
            configure(SerializationFeature.INDENT_OUTPUT, false)
            configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true)
            setDefaultUseWrapper(false)
            addMixIn(SubsonicBody::class.java, SubsonicXmlRootMixin::class.java)
            val module =
                com.fasterxml.jackson.databind.module
                    .SimpleModule()
            module.addSerializer(
                String::class.java,
                object : com.fasterxml.jackson.databind.ser.std.StdScalarSerializer<String>(String::class.java) {
                    override fun serialize(
                        value: String,
                        gen: JsonGenerator,
                        provider: com.fasterxml.jackson.databind.SerializerProvider,
                    ) = gen.writeString(stripInvalidXmlChars(value))
                },
            )
            registerModule(module)
        }

    private fun stripInvalidXmlChars(s: String): String =
        buildString(s.length) {
            for (c in s) {
                val code = c.code
                if (code == 0x09 || code == 0x0A || code == 0x0D || code in 0x20..0xD7FF || code in 0xE000..0xFFFD) {
                    append(c)
                }
            }
        }

    private val jsonMapper =
        com.fasterxml.jackson.databind
            .ObjectMapper()
            .apply {
                registerKotlinModule()
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
            }

    fun render(
        response: SubsonicResponse,
        format: String?,
        callback: String?,
    ): RenderedSubsonicResponse =
        when (format) {
            "json" -> RenderedSubsonicResponse(MediaType.APPLICATION_JSON_VALUE, jsonMapper.writeValueAsString(response))
            "jsonp" ->
                if (callback.isNullOrBlank()) {
                    RenderedSubsonicResponse(
                        MediaType.APPLICATION_JSON_VALUE,
                        jsonMapper.writeValueAsString(error(10, "Required parameter is missing: callback")),
                    )
                } else {
                    RenderedSubsonicResponse(
                        "application/javascript",
                        "$callback(${jsonMapper.writeValueAsString(response)});",
                    )
                }
            else ->
                RenderedSubsonicResponse(
                    MediaType.APPLICATION_XML_VALUE,
                    xmlMapper.writeValueAsString(response.subsonicResponse),
                )
        }

    fun write(
        response: SubsonicResponse,
        format: String?,
    ): ResponseEntity<String> =
        try {
            val rendered = render(response, format, currentRequestParameter("callback"))
            ResponseEntity.ok().contentType(MediaType.parseMediaType(rendered.contentType)).body(rendered.body)
        } catch (e: Exception) {
            log.error("Subsonic response serialization failed (format={})", format, e)
            val errorResponse = error(0, "Serialization error")
            val json = jsonMapper.writeValueAsString(errorResponse)
            ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(json)
        }

    fun writeTo(
        servletResponse: HttpServletResponse,
        response: SubsonicResponse,
        format: String?,
        callback: String?,
    ) {
        val rendered = render(response, format, callback)
        servletResponse.status = HttpServletResponse.SC_OK
        servletResponse.contentType = rendered.contentType
        servletResponse.characterEncoding = Charsets.UTF_8.name()
        servletResponse.writer.write(rendered.body)
    }

    private fun currentRequestParameter(name: String): String? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return attributes.request.getParameter(name)
    }
}
