package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.http.*;

@DisplayName("Feature: Sessions API")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionsApiTest extends BaseIntegrationTest {

  private static String firstTrackId;

  @BeforeAll
  static void findTestData() throws Exception {
    if (authToken == null) return;

    HttpEntity<Void> request = new HttpEntity<>(authHeaders());
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
  @DisplayName("Scenario: Get sessions")
  class GetSessions {

    @Test
    @DisplayName("Given: Authenticated user, When: GET /Sessions, Then: Returns sessions list")
    void getSessions() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(BASE_URL + "/Sessions", HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertNotNull(response.getBody());
    }
  }

  @Nested
  @DisplayName("Scenario: Playback reporting")
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class PlaybackReporting {

    @Test
    @Order(1)
    @DisplayName(
        "Given: Valid track ID, When: POST /Sessions/Playing, Then: Reports playback start")
    void reportPlaybackStart() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = String.format("{\"ItemId\":\"%s\",\"PositionTicks\":0}", firstTrackId);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Sessions/Playing", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @Order(2)
    @DisplayName(
        "Given: Playing track, When: POST /Sessions/Playing/Progress, Then: Reports progress")
    void reportPlaybackProgress() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body =
          String.format(
              "{\"ItemId\":\"%s\",\"PositionTicks\":100000000,\"IsPaused\":false}", firstTrackId);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(
              BASE_URL + "/Sessions/Playing/Progress", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @Order(3)
    @DisplayName("Given: Playing track, When: POST /Sessions/Playing/Stopped, Then: Reports stop")
    void reportPlaybackStopped() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = String.format("{\"ItemId\":\"%s\",\"PositionTicks\":200000000}", firstTrackId);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Sessions/Playing/Stopped", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Session ping")
  class SessionPing {

    @Test
    @DisplayName(
        "Given: Active session, When: POST /Sessions/Playing/Ping, Then: Keeps session alive")
    void pingSession() {
      assumeTrue(authToken != null, "Auth token required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);
      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Sessions/Playing/Ping", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Playback commands")
  class PlaybackCommands {

    @Test
    @DisplayName(
        "Given: Valid session ID, When: POST /Sessions/{id}/Playing/play, Then: Sends command")
    void sendPlayCommand() {
      assumeTrue(authToken != null, "Auth token required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);
      ResponseEntity<String> response =
          restTemplate.postForEntity(
              BASE_URL + "/Sessions/test-session-id/Playing/play", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @DisplayName(
        "Given: Valid session ID, When: POST /Sessions/{id}/Playing/pause, Then: Sends pause"
            + " command")
    void sendPauseCommand() {
      assumeTrue(authToken != null, "Auth token required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);
      ResponseEntity<String> response =
          restTemplate.postForEntity(
              BASE_URL + "/Sessions/test-session-id/Playing/pause", request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Unauthorized access")
  class UnauthorizedAccess {

    @Test
    @DisplayName("Given: No auth token, When: POST /Sessions/Playing, Then: Returns 401")
    void reportPlaybackWithoutAuth() {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = String.format("{\"ItemId\":\"%s\"}", firstTrackId);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      try {
        ResponseEntity<String> response =
            restTemplate.postForEntity(BASE_URL + "/Sessions/Playing", request, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
      } catch (org.springframework.web.client.ResourceAccessException e) {
        assertTrue(
            e.getCause() instanceof java.net.HttpRetryException,
            "Expected HttpRetryException for 401 response");
      }
    }
  }
}
