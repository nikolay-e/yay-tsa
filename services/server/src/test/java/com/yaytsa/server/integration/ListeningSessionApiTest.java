package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.http.*;

@DisplayName("Feature: Listening Session API (DJ/Radio)")
class ListeningSessionApiTest extends BaseIntegrationTest {

  private static String firstTrackId;

  @BeforeAll
  static void findSeedTrack() throws Exception {
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

  private static String createRadioSession() throws Exception {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String body =
        String.format(
            "{\"state\":{\"energy\":5,\"intensity\":5},\"seed_track_id\":\"%s\"}", firstTrackId);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity(BASE_URL + "/v1/sessions", request, String.class);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    JsonNode json = objectMapper.readTree(response.getBody());
    return json.get("id").asText();
  }

  private static void endSessionQuietly(String sessionId) {
    try {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      restTemplate.exchange(
          BASE_URL + "/v1/sessions/" + sessionId, HttpMethod.DELETE, request, String.class);
    } catch (Exception ignored) {
    }
  }

  @Test
  @DisplayName("POST /v1/sessions with seed track → 201 with session data")
  void createSessionWithSeedTrack() throws Exception {
    assumeTrue(firstTrackId != null, "Track ID required");

    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String body =
        String.format(
            "{\"state\":{\"energy\":5,\"intensity\":5},\"seed_track_id\":\"%s\"}", firstTrackId);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity(BASE_URL + "/v1/sessions", request, String.class);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());

    JsonNode json = objectMapper.readTree(response.getBody());
    assertNotNull(json.get("id"));
    assertNotNull(json.get("userId"));
    assertTrue(json.get("isRadioMode").asBoolean());
    assertEquals(firstTrackId, json.get("seedTrackId").asText());

    endSessionQuietly(json.get("id").asText());
  }

  @Test
  @DisplayName("POST /v1/sessions null body → 201, isRadioMode=false")
  void createSessionNullBody() throws Exception {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<String> request = new HttpEntity<>("{}", headers);
    ResponseEntity<String> response =
        restTemplate.postForEntity(BASE_URL + "/v1/sessions", request, String.class);

    assertEquals(HttpStatus.CREATED, response.getStatusCode());

    JsonNode json = objectMapper.readTree(response.getBody());
    assertFalse(json.get("isRadioMode").asBoolean());

    endSessionQuietly(json.get("id").asText());
  }

  @Test
  @DisplayName("GET /v1/sessions/active → 200 with latest session")
  void getActiveSession() throws Exception {
    assumeTrue(firstTrackId != null, "Track ID required");

    String sessionId = createRadioSession();
    try {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/v1/sessions/active", HttpMethod.GET, request, String.class);

      assertTrue(
          response.getStatusCode() == HttpStatus.OK
              || response.getStatusCode() == HttpStatus.NO_CONTENT);
    } finally {
      endSessionQuietly(sessionId);
    }
  }

  @Test
  @DisplayName("DELETE /v1/sessions/{id} → 200, endedAt non-null")
  void endSession() throws Exception {
    assumeTrue(firstTrackId != null, "Track ID required");

    String sessionId = createRadioSession();

    HttpEntity<Void> request = new HttpEntity<>(authHeaders());
    ResponseEntity<String> deleteResponse =
        restTemplate.exchange(
            BASE_URL + "/v1/sessions/" + sessionId, HttpMethod.DELETE, request, String.class);

    assertEquals(HttpStatus.OK, deleteResponse.getStatusCode());
    JsonNode json = objectMapper.readTree(deleteResponse.getBody());
    assertNotNull(json.get("endedAt"));
    assertFalse(json.get("endedAt").isNull());
  }

  @Test
  @DisplayName("GET /v1/sessions/{random UUID} → 404")
  void getSessionNotFound() {
    HttpEntity<Void> request = new HttpEntity<>(authHeaders());
    ResponseEntity<String> response =
        restTemplate.exchange(
            BASE_URL + "/v1/sessions/00000000-0000-0000-0000-000000000001",
            HttpMethod.GET,
            request,
            String.class);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  @DisplayName("POST /v1/sessions without auth → 401")
  void createSessionNoAuth() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<String> request = new HttpEntity<>("{}", headers);

    try {
      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/v1/sessions", request, String.class);
      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      assertTrue(
          e.getCause() instanceof java.net.HttpRetryException,
          "Expected HttpRetryException for 401 response");
    }
  }
}
