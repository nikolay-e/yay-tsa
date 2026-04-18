package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Listening Sessions (FEAT-SESSIONS)")
@Tag("sessions")
class ListeningSessionApiTest extends BaseIntegrationTest {

  private String createRadioSession() throws Exception {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String body =
        String.format(
            "{\"state\":{\"energy\":5,\"intensity\":5},\"seed_track_id\":\"%s\"}", testTrackId1);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity("/v1/sessions", request, String.class);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    JsonNode json = objectMapper.readTree(response.getBody());
    return json.get("id").asText();
  }

  private void endSessionQuietly(String sessionId) {
    try {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      restTemplate.exchange("/v1/sessions/" + sessionId, HttpMethod.DELETE, request, String.class);
    } catch (Exception ignored) {
    }
  }

  @Nested
  @DisplayName("SessionLifecycle")
  class SessionLifecycle {

    @Test
    @DisplayName("AC-01: Create session with seed track")
    @Feature(id = "FEAT-SESSIONS", ac = "AC-01")
    void createSessionWithSeedTrack() throws Exception {
      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body =
          String.format(
              "{\"state\":{\"energy\":5,\"intensity\":5},\"seed_track_id\":\"%s\"}", testTrackId1);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity("/v1/sessions", request, String.class);

      assertEquals(HttpStatus.CREATED, response.getStatusCode());
      assertNotNull(response.getBody());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertNotNull(json.get("id"));
      assertNotNull(json.get("userId"));
      assertTrue(json.get("isRadioMode").asBoolean());
      assertEquals(testTrackId1, json.get("seedTrackId").asText());

      endSessionQuietly(json.get("id").asText());
    }

    @Test
    @DisplayName("AC-02: Create session without seed track")
    @Feature(id = "FEAT-SESSIONS", ac = "AC-02")
    void createSessionNullBody() throws Exception {
      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<String> request = new HttpEntity<>("{}", headers);
      ResponseEntity<String> response =
          restTemplate.postForEntity("/v1/sessions", request, String.class);

      assertEquals(HttpStatus.CREATED, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertFalse(json.get("isRadioMode").asBoolean());

      endSessionQuietly(json.get("id").asText());
    }

    @Test
    @DisplayName("AC-03: End session sets endedAt")
    @Feature(id = "FEAT-SESSIONS", ac = "AC-03")
    void endSession() throws Exception {
      String sessionId = createRadioSession();

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> deleteResponse =
          restTemplate.exchange(
              "/v1/sessions/" + sessionId, HttpMethod.DELETE, request, String.class);

      assertEquals(HttpStatus.OK, deleteResponse.getStatusCode());
      JsonNode json = objectMapper.readTree(deleteResponse.getBody());
      assertNotNull(json.get("endedAt"));
      assertFalse(json.get("endedAt").isNull());
    }
  }
}
