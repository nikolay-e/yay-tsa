package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Karaoke API (FEAT-KARAOKE)")
@Tag("karaoke")
class KaraokeApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("Status")
  class Status {

    @Test
    @DisplayName("AC-01: Non-existent track returns ok status")
    @Feature(id = "FEAT-KARAOKE", ac = "AC-01")
    void getKaraokeStatusNonExistent() throws Exception {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              "/Karaoke/00000000-0000-0000-0000-000000000000/status?api_key=" + authToken,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Processing")
  class Processing {

    @Test
    @DisplayName("AC-02: Process request returns 202")
    @Feature(id = "FEAT-KARAOKE", ac = "AC-02")
    void requestKaraokeProcessing() throws Exception {
      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> request = new HttpEntity<>("{}", headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(
              "/Karaoke/" + testTrackId1 + "/process", request, String.class);

      assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("state"));
    }
  }

  @Nested
  @DisplayName("StemStreaming")
  class StemStreaming {

    @Test
    @DisplayName("AC-03: Instrumental without stems returns 404")
    @Feature(id = "FEAT-KARAOKE", ac = "AC-03")
    void streamInstrumentalNotReady() throws Exception {
      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              "/Karaoke/" + testTrackId1 + "/instrumental?api_key=" + authToken, byte[].class);

      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("AC-03: Vocals without stems returns 404")
    @Feature(id = "FEAT-KARAOKE", ac = "AC-03")
    void streamVocalsNotReady() throws Exception {
      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              "/Karaoke/" + testTrackId1 + "/vocals?api_key=" + authToken, byte[].class);

      assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
  }
}
