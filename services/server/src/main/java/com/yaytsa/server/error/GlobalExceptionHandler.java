package com.yaytsa.server.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatusException(
      ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail problem = createProblemDetail(status, ex.getReason(), request);

    if (status.is5xxServerError()) {
      log.error(
          "ResponseStatusException: status={}, path={}, reason={}",
          status.value(),
          request.getRequestURI(),
          ex.getReason(),
          ex);
    } else if (status == HttpStatus.FORBIDDEN) {
      log.warn(
          "Access denied: status={}, path={}, reason={}",
          status.value(),
          request.getRequestURI(),
          ex.getReason());
    } else {
      log.debug(
          "ResponseStatusException: status={}, path={}, reason={}",
          status.value(),
          request.getRequestURI(),
          ex.getReason());
    }

    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, ex.getMessage(), request);

    log.warn("Bad request: path={}, error={}", request.getRequestURI(), ex.getMessage());

    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String message =
        String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
    ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, message, request);

    log.warn(
        "Type mismatch: path={}, param={}, value={}",
        request.getRequestURI(),
        ex.getName(),
        ex.getValue());

    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ProblemDetail> handleMissingParam(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    String message = String.format("Missing required parameter: %s", ex.getParameterName());
    ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, message, request);

    log.warn(
        "Missing parameter: path={}, param={}", request.getRequestURI(), ex.getParameterName());

    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest request) {
    ProblemDetail problem =
        createProblemDetail(HttpStatus.NOT_FOUND, "Resource not found", request);

    log.debug("Resource not found: path={}", request.getRequestURI());

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthenticationException(
      AuthenticationException ex, HttpServletRequest request) {
    ProblemDetail problem =
        createProblemDetail(HttpStatus.UNAUTHORIZED, "Authentication required", request);

    log.warn("Authentication failed: path={}", request.getRequestURI());

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ProblemDetail problem = createProblemDetail(HttpStatus.FORBIDDEN, "Access denied", request);

    log.warn("Access denied: path={}, user={}", request.getRequestURI(), MDC.get("userId"));

    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleGenericException(
      Exception ex, HttpServletRequest request) {
    ProblemDetail problem =
        createProblemDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);

    log.error(
        "Unhandled exception: path={}, type={}, message={}",
        request.getRequestURI(),
        ex.getClass().getSimpleName(),
        ex.getMessage(),
        ex);

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }

  private ProblemDetail createProblemDetail(
      HttpStatus status, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("path", request.getRequestURI());
    problem.setProperty("timestamp", Instant.now().toString());

    String requestId = MDC.get("requestId");
    if (requestId != null) {
      problem.setProperty("requestId", requestId);
    }

    return problem;
  }
}
