package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Streaming API")
class StreamingApiTest extends BaseIntegrationTest {

  private static String firstTrackId;

  @BeforeAll
  static void findTrack() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + authToken);
    HttpEntity<Void> request = new HttpEntity<>(headers);

    ResponseEntity<String> response =
        restTemplate.exchange(
            BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1",
            HttpMethod.GET,
            request,
            String.class);

    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
      JsonNode json = objectMapper.readTree(response.getBody());
      if (json.get("TotalRecordCount").asInt() > 0) {
        firstTrackId = json.get("Items").get(0).get("Id").asText();
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Stream audio file")
  class StreamAudio {

    @Test
    @DisplayName("Given: Valid track ID, When: GET /Audio/{id}/stream, Then: Returns audio stream")
    void streamAudioFile() {
      assumeTrue(firstTrackId != null, "Track ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?api_key=" + authToken + "&static=true",
              byte[].class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertNotNull(response.getHeaders().getContentType());
      assertEquals("bytes", response.getHeaders().getFirst("Accept-Ranges"));
    }

    @Test
    @DisplayName(
        "Given: Valid track ID with Range header, When: GET /Audio/{id}/stream, Then: Returns 206"
            + " Partial Content")
    void streamAudioFileWithRange() {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=0-1023");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.GET,
              request,
              byte[].class);

      assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
      assertNotNull(response.getHeaders().getFirst("Content-Range"));
    }
  }

  @Nested
  @DisplayName("Scenario: Stream with invalid parameters")
  class InvalidStreamRequests {

    @Test
    @DisplayName("Given: Non-existent track ID, When: GET /Audio/{id}/stream, Then: Returns 404")
    void streamNonExistentTrack() {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL
                  + "/Audio/00000000-0000-0000-0000-000000000000/stream?api_key="
                  + authToken
                  + "&static=true",
              String.class);

      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Given: No auth token, When: GET /Audio/{id}/stream, Then: Returns 401")
    void streamWithoutAuth() {
      assumeTrue(firstTrackId != null, "Track ID required");

      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?static=true", String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Streaming response integrity")
  class StreamResponseIntegrity {

    @Test
    @DisplayName(
        "Given: Range request, When: GET /Audio/{id}/stream, Then: Response body length matches"
            + " Content-Range")
    void rangeResponseBodyMatchesContentRange() {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=0-4095");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?api_key=" + authToken + "&static=true",
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
    @DisplayName(
        "Given: HEAD request, When: HEAD /Audio/{id}/stream, Then: Returns Content-Length > 0")
    void headRequestReturnsContentLength() {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?api_key=" + authToken + "&static=true",
              HttpMethod.HEAD,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertTrue(
          response.getHeaders().getContentLength() > 0,
          "HEAD response must have Content-Length > 0");
    }

    @Test
    @DisplayName(
        "Given: Range beyond file size, When: GET /Audio/{id}/stream, Then: Returns 416 or partial"
            + " content")
    void rangeBeyondFileSize() {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = new HttpHeaders();
      headers.set("Range", "bytes=999999999999-999999999999");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> response =
          restTemplate.exchange(
              BASE_URL + "/Audio/" + firstTrackId + "/stream?api_key=" + authToken + "&static=true",
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
