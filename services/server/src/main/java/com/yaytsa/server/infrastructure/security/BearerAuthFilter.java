package com.yaytsa.server.infrastructure.security;

import com.yaytsa.server.domain.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class BearerAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String EMBY_TOKEN_HEADER = "X-Emby-Token";
  private static final String EMBY_AUTH_HEADER = "X-Emby-Authorization";
  private static final String API_KEY_PARAM = "api_key";

  private static final List<RequestMatcher> PUBLIC_PATH_MATCHERS =
      List.of(
          new AntPathRequestMatcher("/Users/AuthenticateByName"),
          new AntPathRequestMatcher("/System/Info/Public"),
          new AntPathRequestMatcher("/error"),
          new AntPathRequestMatcher("/api-docs/**"),
          new AntPathRequestMatcher("/swagger-ui/**"),
          new AntPathRequestMatcher("/swagger-ui.html"),
          new AntPathRequestMatcher("/v3/api-docs/**"),
          new AntPathRequestMatcher("/manage/health"),
          new AntPathRequestMatcher("/manage/health/**"));

  private final AuthService authService;

  public BearerAuthFilter(AuthService authService) {
    this.authService = authService;
  }

  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    return PUBLIC_PATH_MATCHERS.stream().anyMatch(matcher -> matcher.matches(request));
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    try {
      Optional<String> token = extractToken(request);

      if (token.isPresent()) {
        Optional<AuthService.TokenValidationResult> result = authService.validateToken(token.get());

        if (result.isPresent()) {
          AuthService.TokenValidationResult validated = result.get();
          AuthenticatedUser authenticatedUser =
              new AuthenticatedUser(
                  validated.user(), token.get(), validated.deviceId(), validated.deviceName());

          UsernamePasswordAuthenticationToken authentication =
              new UsernamePasswordAuthenticationToken(
                  authenticatedUser, null, authenticatedUser.getAuthorities());

          authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authentication);

          log.debug(
              "Authenticated user: {} with device: {}",
              authenticatedUser.getUsername(),
              validated.deviceId());
        } else {
          log.debug("Invalid token provided");
        }
      }
    } catch (Exception e) {
      log.warn("Authentication processing failed: {}", e.getMessage());
    }

    filterChain.doFilter(request, response);
  }

  private Optional<String> extractToken(HttpServletRequest request) {
    String embyToken = request.getHeader(EMBY_TOKEN_HEADER);
    if (embyToken != null && !embyToken.isBlank()) {
      return Optional.of(embyToken);
    }

    String embyAuth = request.getHeader(EMBY_AUTH_HEADER);
    if (embyAuth != null) {
      Optional<String> extracted = extractTokenFromEmbyAuth(embyAuth);
      if (extracted.isPresent()) {
        return extracted;
      }
    }

    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
      String token = authHeader.substring(BEARER_PREFIX.length()).trim();
      if (!token.isBlank()) {
        return Optional.of(token);
      }
    }

    String apiKeyParam = request.getParameter(API_KEY_PARAM);
    if (apiKeyParam != null && !apiKeyParam.isBlank()) {
      return Optional.of(apiKeyParam);
    }

    return Optional.empty();
  }

  private Optional<String> extractTokenFromEmbyAuth(String header) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("Token=\"([^\"]+)\"").matcher(header);
    if (m.find()) {
      return Optional.of(m.group(1));
    }
    return Optional.empty();
  }
}
