package com.yaytsa.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.security.EmbyAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  private final EmbyAuthFilter embyAuthFilter;
  private final ObjectMapper objectMapper;

  @Value("${yaytsa.security.cors.enabled:true}")
  private boolean corsEnabled;

  @Value("${yaytsa.security.cors.allowed-origins:*}")
  private String allowedOrigins;

  public SecurityConfig(EmbyAuthFilter embyAuthFilter, ObjectMapper objectMapper) {
    this.embyAuthFilter = embyAuthFilter;
    this.objectMapper = objectMapper;
  }

  @Bean
  @SuppressWarnings("java:S4502") // CSRF disabled intentionally: stateless REST API with token auth
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // CSRF protection not needed: SessionCreationPolicy.STATELESS + token-based auth (no cookies)
    http.csrf(csrf -> csrf.disable()) // lgtm[java/spring-disabled-csrf-protection]
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .cors(
            cors -> {
              if (corsEnabled) {
                cors.configurationSource(corsConfigurationSource());
              }
            })
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exception ->
                exception
                    .authenticationEntryPoint(
                        (request, response, authException) -> {
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                          objectMapper.writeValue(
                              response.getOutputStream(),
                              Map.of(
                                  "status",
                                  401,
                                  "error",
                                  "Unauthorized",
                                  "path",
                                  request.getServletPath()));
                        })
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) -> {
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                          objectMapper.writeValue(
                              response.getOutputStream(),
                              Map.of(
                                  "status",
                                  403,
                                  "error",
                                  "Forbidden",
                                  "path",
                                  request.getServletPath()));
                        }))
        .headers(
            headers ->
                headers
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                    .contentTypeOptions(
                        org.springframework.security.config.Customizer.withDefaults())
                    .frameOptions(frame -> frame.deny())
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                    .permissionsPolicy(
                        pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/manage/health", "/manage/health/**")
                    .permitAll()
                    .requestMatchers("/manage/**")
                    .denyAll()
                    .requestMatchers("/System/Info/Public")
                    .permitAll()
                    .requestMatchers("/Users/AuthenticateByName")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(embyAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  @Value("${yaytsa.security.cors.allow-credentials:false}")
  private boolean allowCredentials;

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    // Split and trim origins to handle whitespace around commas
    var origins =
        Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();

    if (allowedOrigins.contains("*")) {
      configuration.setAllowedOriginPatterns(origins);
    } else {
      configuration.setAllowedOrigins(origins);
    }

    configuration.setAllowedMethods(
        Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
    configuration.setAllowedHeaders(Arrays.asList("*"));
    configuration.setExposedHeaders(
        Arrays.asList(
            "Content-Range",
            "Accept-Ranges",
            "X-Total-Count",
            "Content-Disposition",
            "X-Emby-Authorization"));
    configuration.setAllowCredentials(allowCredentials);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
