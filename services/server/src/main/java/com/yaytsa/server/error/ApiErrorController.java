package com.yaytsa.server.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {

  // CSRF suppressed: /error is permitAll() in SecurityConfig, not a user-facing endpoint
  @SuppressWarnings("codeql[java/spring-disabled-csrf-protection]")
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING")
  @RequestMapping(
      value = "/error",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.DELETE,
        RequestMethod.PATCH,
        RequestMethod.HEAD
      },
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProblemDetail> handleError(HttpServletRequest request) {
    Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (statusCode == null) {
      ProblemDetail notFound = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not Found");
      notFound.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
      notFound.setProperty("path", request.getRequestURI());
      notFound.setProperty("timestamp", Instant.now().toString());
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_JSON)
          .body(notFound);
    }

    HttpStatus status = resolveStatus(request);
    String message = resolveMessage(request, status);
    String path = resolvePath(request);

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("path", path);
    problem.setProperty("timestamp", Instant.now().toString());

    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(problem);
  }

  private HttpStatus resolveStatus(HttpServletRequest request) {
    Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (statusCode instanceof Integer code) {
      try {
        return HttpStatus.valueOf(code);
      } catch (IllegalArgumentException ignored) {
        return HttpStatus.INTERNAL_SERVER_ERROR;
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private String resolveMessage(HttpServletRequest request, HttpStatus status) {
    Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
    if (message instanceof String msg && !msg.isBlank()) {
      return msg;
    }
    return status.getReasonPhrase();
  }

  private String resolvePath(HttpServletRequest request) {
    Object requestUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    if (requestUri instanceof String uri) {
      return uri;
    }
    return request.getRequestURI();
  }
}
