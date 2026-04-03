package com.yaytsa.server.infrastructure.security;

import com.yaytsa.server.domain.service.AuthService;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class EmbyAuthFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(EmbyAuthFilter.class);

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

  public EmbyAuthFilter(AuthService authService) {
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

    Optional<EmbyAuthCredentials> credentials = extractCredentials(request);

    if (credentials.isPresent()) {
      String token = credentials.get().token();
      String deviceId = credentials.get().deviceId();
      String deviceName = credentials.get().deviceName();

      Optional<UserEntity> userEntity = authService.validateToken(token);

      if (userEntity.isPresent()) {
        AuthenticatedUser authenticatedUser =
            new AuthenticatedUser(userEntity.get(), token, deviceId, deviceName);

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                authenticatedUser, null, authenticatedUser.getAuthorities());

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug(
            "Authenticated user: {} with device: {}", authenticatedUser.getUsername(), deviceId);
      } else {
        log.debug("Invalid token provided");
      }
    }

    filterChain.doFilter(request, response);
  }

  private Optional<EmbyAuthCredentials> extractCredentials(HttpServletRequest request) {
    String embyAuthHeader = request.getHeader(EMBY_AUTH_HEADER);
    if (embyAuthHeader != null && !embyAuthHeader.isBlank()) {
      return Optional.of(EmbyAuthHeaderParser.parseToCredentials(embyAuthHeader));
    }

    String apiKeyParam = request.getParameter(API_KEY_PARAM);
    String deviceIdParam = request.getParameter("deviceId");
    if (apiKeyParam != null && !apiKeyParam.isBlank()) {
      return Optional.of(
          EmbyAuthCredentials.fromQueryParams(
              apiKeyParam, deviceIdParam != null ? deviceIdParam : "unknown"));
    }

    return Optional.empty();
  }
}
