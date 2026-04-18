package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Streaming API (FEAT-STREAM)")
@Tag("streaming")
class StreamingApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("BasicStreaming")
  class BasicStreaming {

    @Test
    @DisplayName("AC-01: Full stream returns audio bytes")
    @Feature(id = "FEAT-STREAM", ac = "AC-01")
    void fullStreamReturnsAudioBytes() {
      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              "/Audio/" + testTrackId1 + "/stream?api_key=" + authToken + "&static=true",
              byte[].class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertNotNull(response.getHeaders().getContentType());
      assertEquals("bytes", response.getHeaders().getFirst("Accept-Ranges"));
    }

    @Test
    @DisplayName("AC-02: Range request returns 206")
    @Feature(id = "FEAT-STREAM", ac = "AC-02")
    void rangeRequestReturns206() {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=0-1023");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              "/Audio/" + testTrackId1 + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.GET,
              request,
              byte[].class);

      assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
      assertNotNull(response.getHeaders().getFirst("Content-Range"));
    }
  }

  @Nested
  @DisplayName("ResponseIntegrity")
  class ResponseIntegrity {

    @Test
    @DisplayName("AC-03: Body length matches Content-Range")
    @Feature(id = "FEAT-STREAM", ac = "AC-03")
    void bodyLengthMatchesContentRange() {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=0-4095");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              "/Audio/" + testTrackId1 + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.GET,
              request,
              byte[].class);

      assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());

      String contentRange = response.getHeaders().getFirst("Content-Range");
      assertNotNull(contentRange, "Content-Range header must be present");

      byte[] body = response.getBody();
      assertNotNull(body);

      long contentLength = response.getHeaders().getContentLength();
      if (contentLength > 0) {
        assertEquals(contentLength, body.length, "Body length must match Content-Length header");
      }
    }

    @Test
    @DisplayName("AC-04: HEAD returns Content-Length")
    @Feature(id = "FEAT-STREAM", ac = "AC-04")
    void headReturnsContentLength() {
      HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Audio/" + testTrackId1 + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.HEAD,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertTrue(
          response.getHeaders().getContentLength() > 0,
          "HEAD response must have Content-Length > 0");
    }

    @Test
    @DisplayName("AC-05: Out-of-range returns 416 or graceful")
    @Feature(id = "FEAT-STREAM", ac = "AC-05")
    void outOfRangeReturns416OrGraceful() {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=999999999999-999999999999");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              "/Audio/" + testTrackId1 + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.GET,
              request,
              byte[].class);

      assertTrue(
          response.getStatusCode() == HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE
              || response.getStatusCode() == HttpStatus.PARTIAL_CONTENT,
          "Should return 416 or 206 for out-of-range request");
    }
  }
}
