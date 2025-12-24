package com.example.mediaserver.infra.security;

import com.example.mediaserver.domain.service.AuthService;
import com.example.mediaserver.infra.persistence.entity.UserEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class EmbyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EmbyAuthFilter.class);

    private static final String EMBY_AUTH_HEADER = "X-Emby-Authorization";
    private static final String API_KEY_PARAM = "api_key";

    private final AuthService authService;

    public EmbyAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (shouldSkipAuthentication(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<EmbyAuthCredentials> credentials = extractCredentials(request);

        if (credentials.isPresent()) {
            String token = credentials.get().token();
            String deviceId = credentials.get().deviceId();

            Optional<UserEntity> userEntity = authService.validateToken(token);

            if (userEntity.isPresent()) {
                AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                    userEntity.get(),
                    token,
                    deviceId
                );

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        authenticatedUser,
                        null,
                        authenticatedUser.getAuthorities()
                    );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("Authenticated user: {} with device: {}",
                    authenticatedUser.getUsername(), deviceId);
            } else {
                log.debug("Invalid token provided");
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldSkipAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/Users/AuthenticateByName") ||
               path.equals("/System/Info/Public") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/api-docs") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/manage/health");
    }

    private Optional<EmbyAuthCredentials> extractCredentials(HttpServletRequest request) {
        String embyAuthHeader = request.getHeader(EMBY_AUTH_HEADER);
        if (embyAuthHeader != null && !embyAuthHeader.isBlank()) {
            return Optional.of(parseEmbyAuthHeader(embyAuthHeader));
        }

        String apiKeyParam = request.getParameter(API_KEY_PARAM);
        String deviceIdParam = request.getParameter("deviceId");
        if (apiKeyParam != null && !apiKeyParam.isBlank()) {
            return Optional.of(EmbyAuthCredentials.fromQueryParams(
                apiKeyParam,
                deviceIdParam != null ? deviceIdParam : "unknown"
            ));
        }

        return Optional.empty();
    }

    private EmbyAuthCredentials parseEmbyAuthHeader(String header) {
        Map<String, String> parts = new HashMap<>();

        String content = header;
        if (content.startsWith("MediaBrowser ")) {
            content = content.substring("MediaBrowser ".length());
        }

        String[] pairs = content.split(",");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            int equalPos = trimmed.indexOf('=');
            if (equalPos > 0) {
                String key = trimmed.substring(0, equalPos).trim();
                String value = trimmed.substring(equalPos + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                parts.put(key, value);
            }
        }

        return new EmbyAuthCredentials(
            parts.get("Token"),
            parts.getOrDefault("DeviceId", "unknown"),
            parts.get("Device"),
            parts.get("Client"),
            parts.get("Version")
        );
    }
}
