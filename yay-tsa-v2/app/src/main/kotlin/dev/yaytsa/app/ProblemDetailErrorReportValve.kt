package dev.yaytsa.app

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus

// Tomcat rejects malformed request targets (e.g. an illegal character such as ']') in its HTTP
// parser, before the request ever reaches the servlet container. Such failures bypass both the
// @RestControllerAdvice and the /error controller, so Tomcat's ErrorReportValve renders an HTML
// page — breaking the RFC 7807 application/problem+json contract the rest of the API honours. This
// valve re-renders those container-level errors as problem+json. Responses already produced by the
// servlet layer (security 401/403, /error forwards, normal handler results) are committed by the
// time report() runs, so the isCommitted guard leaves them untouched.
class ProblemDetailErrorReportValve : ErrorReportValve() {
    override fun report(
        request: Request,
        response: Response,
        throwable: Throwable?,
    ) {
        val status = response.status
        if (status < 400 || response.isCommitted) return
        // getReporter() is the writer Tomcat exposes for error rendering; it is null once the
        // response output is unavailable, which doubles as the anti-double-write guard.
        val writer = response.reporter ?: return

        val reason = runCatching { HttpStatus.valueOf(status).reasonPhrase }.getOrDefault("Error")
        response.setContentType("application/problem+json")
        response.characterEncoding = Charsets.UTF_8.name()
        writer.write(
            """{"type":"about:blank","title":"$reason","status":$status,"detail":"$reason"}""",
        )
    }
}

@Configuration
class TomcatErrorValveConfig {
    // StandardHost installs its default ErrorReportValve at startup unless the pipeline already holds
    // a valve whose class name matches errorReportValveClass. Pointing that class at our subclass
    // makes Tomcat instantiate ours as THE host reporter (no default added); stripping any valve
    // Spring Boot pre-registered guarantees ours is the only one. Lowest precedence so this runs
    // after Spring Boot's own customizer.
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun problemDetailErrorReportValveCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addContextCustomizers(
                TomcatContextCustomizer { context ->
                    val host = context.parent as StandardHost
                    host.pipeline.valves
                        .filterIsInstance<ErrorReportValve>()
                        .forEach(host.pipeline::removeValve)
                    host.errorReportValveClass = ProblemDetailErrorReportValve::class.java.name
                },
            )
        }
}
