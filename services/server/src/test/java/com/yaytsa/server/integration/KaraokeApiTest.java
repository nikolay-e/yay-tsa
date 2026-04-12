package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Karaoke API")
class KaraokeApiTest extends BaseIntegrationTest {

  private static String firstTrackId;

  @BeforeAll
  static void findTestData() throws Exception {
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
  @DisplayName("Scenario: Get karaoke status")
  class GetKaraokeStatus {

    @Test
    @DisplayName(
        "Given: Non-existent track ID, When: GET /Karaoke/{id}/status, Then: Returns status (no"
            + " error)")
    void getKaraokeStatusNonExistent() throws Exception {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL
                  + "/Karaoke/00000000-0000-0000-0000-000000000000/status?api_key="
                  + authToken,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Request karaoke processing")
  class RequestProcessing {

    @Test
    @DisplayName("Given: Valid track ID, When: POST /Karaoke/{id}/process, Then: Starts processing")
    void requestKaraokeProcessing() throws Exception {
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> request = new HttpEntity<>("{}", headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(
              BASE_URL + "/Karaoke/" + firstTrackId + "/process", request, String.class);

      assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("state"));
    }
  }

  @Nested
  @DisplayName("Scenario: Stream karaoke files")
  class StreamKaraokeFiles {

    @Test
    @DisplayName(
        "Given: Track without stems, When: GET /Karaoke/{id}/instrumental, Then: Returns 404")
    void streamInstrumentalNotReady() {
      assumeTrue(firstTrackId != null, "Track ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Karaoke/" + firstTrackId + "/instrumental?api_key=" + authToken,
              byte[].class);

      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Given: Track without stems, When: GET /Karaoke/{id}/vocals, Then: Returns 404")
    void streamVocalsNotReady() {
      assumeTrue(firstTrackId != null, "Track ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Karaoke/" + firstTrackId + "/vocals?api_key=" + authToken, byte[].class);

      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
  }
}
