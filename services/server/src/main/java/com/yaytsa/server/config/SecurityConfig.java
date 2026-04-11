package com.yaytsa.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.security.BearerAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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

  private final BearerAuthFilter bearerAuthFilter;
  private final ObjectMapper objectMapper;

  @Value("${yaytsa.security.cors.enabled:true}")
  private boolean corsEnabled;

  @Value("${yaytsa.security.cors.allowed-origins:*}")
  private String allowedOrigins;

  public SecurityConfig(BearerAuthFilter bearerAuthFilter, ObjectMapper objectMapper) {
    this.bearerAuthFilter = bearerAuthFilter;
    this.objectMapper = objectMapper;
  }

  @Bean
  // CSRF disabled: stateless token-based API, no cookie auth
  @SuppressWarnings({"java:S4502", "codeql[java/spring-disabled-csrf-protection]"})
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
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
                          if (response.isCommitted()) return;
                          writeErrorResponse(
                              request,
                              response,
                              HttpStatus.UNAUTHORIZED,
                              "Authentication required");
                        })
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) -> {
                          if (response.isCommitted()) return;
                          writeErrorResponse(
                              request, response, HttpStatus.FORBIDDEN, "Access denied");
                        }))
        .headers(
            headers ->
                headers
                    .contentTypeOptions(
                        org.springframework.security.config.Customizer.withDefaults())
                    .frameOptions(frame -> frame.deny()))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
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
        .addFilterBefore(bearerAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
        Arrays.asList("Content-Range", "Accept-Ranges", "X-Total-Count", "Content-Disposition"));
    configuration.setAllowCredentials(allowCredentials);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private void writeErrorResponse(
      HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
      throws java.io.IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("path", request.getServletPath());
    problem.setProperty("timestamp", Instant.now().toString());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setStatus(status.value());
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
