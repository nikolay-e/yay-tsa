package dev.yaytsa.app.security

import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val apiTokenAuthFilter: ApiTokenAuthFilter,
    private val jellyfinAuthFilter: dev.yaytsa.adapterjellyfin.JellyfinAuthFilter,
    private val subsonicAuthFilter: dev.yaytsa.adapteropensubsonic.SubsonicAuthFilter,
    private val problemDetailSecurityHandler: ProblemDetailSecurityHandler,
    meterRegistry: io.micrometer.core.instrument.MeterRegistry,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${yaytsa.auth.max-failures-per-minute:10}")
    maxAuthFailuresPerMinute: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Deliberately not a bean: Spring Boot auto-registers Filter beans at the servlet level,
    // which would run it outside the security chain where the post-auth SecurityContext
    // (needed to detect failed Subsonic credentials) is already cleared.
    private val authRateLimitFilter = AuthRateLimitFilter(meterRegistry, objectMapper, maxAuthFailuresPerMinute)

    @Bean
    @Order(0)
    fun managementPortSecurityFilterChain(
        http: HttpSecurity,
        environment: Environment,
    ): SecurityFilterChain {
        val managementPort = environment.getProperty("management.server.port")?.toIntOrNull()
        val serverPort = environment.getProperty("server.port")?.toIntOrNull() ?: 8080
        val isolatedManagementPort = managementPort != null && managementPort != serverPort
        http
            .securityMatcher { request -> isolatedManagementPort && request.localPort == managementPort }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    @Order(1)
    fun securityFilterChain(
        http: HttpSecurity,
        environment: Environment,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { cors -> cors.configurationSource(corsConfigurationSource(environment)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/ws/**")
                    .permitAll()
                    .requestMatchers("/Users/AuthenticateByName")
                    .permitAll()
                    .requestMatchers("/System/Info/Public", "/System/Ping")
                    .permitAll()
                    .requestMatchers("/v1/time")
                    .permitAll()
                    .requestMatchers("/v1/client-errors")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/v3/api-docs")
                    .permitAll()
                    .requestMatchers(
                        "/manage/health",
                        "/manage/health/liveness",
                        "/manage/health/readiness",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(problemDetailSecurityHandler)
                it.accessDeniedHandler(problemDetailSecurityHandler)
            }.addFilterAfter(authRateLimitFilter, org.springframework.security.web.context.SecurityContextHolderFilter::class.java)
            .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jellyfinAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(subsonicAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private fun corsConfigurationSource(environment: Environment): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        val corsEnabled = environment.getProperty("CORS_ENABLED")?.toBooleanStrictOrNull() ?: true
        if (!corsEnabled) {
            source.registerCorsConfiguration("/**", CorsConfiguration())
            return source
        }

        val origins =
            (environment.getProperty("CORS_ORIGINS") ?: "http://localhost:*")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        val configuration =
            CorsConfiguration().apply {
                allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                allowedHeaders = listOf("*")
                maxAge = 3600
            }

        if (origins.any { it == "*" }) {
            log.warn(
                "CORS_ORIGINS contains a wildcard '*', which browsers forbid together with credentials; " +
                    "downgrading to allowCredentials=false for the wildcard origin.",
            )
            configuration.allowedOriginPatterns = listOf("*")
            configuration.allowCredentials = false
        } else {
            configuration.allowedOriginPatterns = origins
            configuration.allowCredentials = true
        }

        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
