package dev.yaytsa.app.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    private val rateLimitFilter: RateLimitFilter,
) {
    @Bean
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
                    .requestMatchers("/ws/**")
                    .permitAll()
                    .requestMatchers("/Users/AuthenticateByName")
                    .permitAll()
                    .requestMatchers("/System/Info/Public", "/System/Ping")
                    .permitAll()
                    .requestMatchers("/manage/health", "/manage/health/liveness", "/manage/health/readiness")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.sendError(401, "Unauthorized")
                }
            }.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jellyfinAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(subsonicAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
