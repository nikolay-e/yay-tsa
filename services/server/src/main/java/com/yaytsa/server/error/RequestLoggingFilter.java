package com.yaytsa.server.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String MDC_REQUEST_ID = "requestId";
  private static final String MDC_METHOD = "method";
  private static final String MDC_PATH = "path";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    long startTime = System.currentTimeMillis();
    String requestId = getOrCreateRequestId(request);
    String method = request.getMethod();
    String path = request.getRequestURI();
    String queryString = request.getQueryString();

    MDC.put(MDC_REQUEST_ID, requestId);
    MDC.put(MDC_METHOD, method);
    MDC.put(MDC_PATH, path);

    response.setHeader(REQUEST_ID_HEADER, requestId);

    try {
      if (shouldLogRequest(path)) {
        String fullPath = queryString != null ? path + "?" + sanitizeQuery(queryString) : path;
        log.info("→ {} {}", method, fullPath);
      }

      filterChain.doFilter(request, response);

    } finally {
      long duration = System.currentTimeMillis() - startTime;
      int status = response.getStatus();

      if (shouldLogRequest(path)) {
        if (status >= 500) {
          log.error("← {} {} {} {}ms", method, path, status, duration);
        } else if (status >= 400) {
          log.warn("← {} {} {} {}ms", method, path, status, duration);
        } else {
          log.info("← {} {} {} {}ms", method, path, status, duration);
        }
      }

      MDC.remove(MDC_REQUEST_ID);
      MDC.remove(MDC_METHOD);
      MDC.remove(MDC_PATH);
    }
  }

  private String getOrCreateRequestId(HttpServletRequest request) {
    String requestId = request.getHeader(REQUEST_ID_HEADER);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString().substring(0, 8);
    }
    return requestId;
  }

  private boolean shouldLogRequest(String path) {
    return !path.startsWith("/manage/health") && !path.startsWith("/favicon") && !path.equals("/");
  }

  private String sanitizeQuery(String queryString) {
    return queryString
        .replaceAll("api_key=[^&]*", "api_key=***")
        .replaceAll("token=[^&]*", "token=***")
        .replaceAll("password=[^&]*", "password=***");
  }
}
