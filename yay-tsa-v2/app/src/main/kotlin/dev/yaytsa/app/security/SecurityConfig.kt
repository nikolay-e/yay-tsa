package dev.yaytsa.app.security

import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val apiTokenAuthFilter: ApiTokenAuthFilter,
    private val jellyfinAuthFilter: dev.yaytsa.adapterjellyfin.JellyfinAuthFilter,
    private val subsonicAuthFilter: dev.yaytsa.adapteropensubsonic.SubsonicAuthFilter,
    private val problemDetailSecurityHandler: ProblemDetailSecurityHandler,
) {
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
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { cors ->
                cors.configurationSource {
                    org.springframework.web.cors.CorsConfiguration().apply {
                        allowedOriginPatterns =
                            listOf(
                                System.getenv("CORS_ORIGINS") ?: "http://localhost:*",
                            )
                        allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                        allowedHeaders = listOf("*")
                        allowCredentials = true
                        maxAge = 3600
                    }
                }
            }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
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
            }.addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jellyfinAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(subsonicAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
