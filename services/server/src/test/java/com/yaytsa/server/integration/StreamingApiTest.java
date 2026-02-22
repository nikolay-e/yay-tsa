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
    if (authToken == null) return;

    HttpHeaders headers = new HttpHeaders();
    headers.set(
        "X-Emby-Authorization",
        "MediaBrowser Client=TestClient, Device=TestDevice, DeviceId=test-123, Version=1.0, Token="
            + authToken);
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
      assumeTrue(authToken != null, "Auth token required");
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
      assumeTrue(authToken != null, "Auth token required");
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
      assumeTrue(authToken != null, "Auth token required");

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
}
