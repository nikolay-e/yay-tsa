package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Items API")
class ItemsApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("Scenario: Get music albums")
  class GetAlbums {

    @Test
    @DisplayName(
        "Given: Authenticated user, When: GET /Items with MusicAlbum filter, Then: Returns albums"
            + " list")
    void getAlbums() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
      assertTrue(json.get("Items").isArray());
    }

    @Test
    @DisplayName(
        "Given: Authenticated user, When: GET /Items with pagination, Then: Returns paginated"
            + " results")
    void getAlbumsWithPagination() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&StartIndex=0&Limit=10",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(0, json.get("StartIndex").asInt());
      assertTrue(json.has("TotalRecordCount"));
    }
  }

  @Nested
  @DisplayName("Scenario: Get audio tracks")
  class GetTracks {

    @Test
    @DisplayName(
        "Given: Authenticated user, When: GET /Items with Audio filter, Then: Returns tracks list")
    void getTracks() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }
  }

  @Nested
  @DisplayName("Scenario: Get artists")
  class GetArtists {

    @Test
    @DisplayName(
        "Given: Authenticated user, When: GET /Items with MusicArtist filter, Then: Returns artists"
            + " list")
    void getArtists() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=MusicArtist&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }
  }

  @Nested
  @DisplayName("Scenario: Unauthorized access")
  class UnauthorizedAccess {

    @Test
    @DisplayName("Given: No auth token, When: GET /Items, Then: Returns 401")
    void getItemsWithoutAuth() {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true", String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("Given: Invalid token, When: GET /Items, Then: Returns 401")
    void getItemsWithInvalidToken() {
      HttpHeaders headers = new HttpHeaders();
      headers.set(
          "X-Emby-Authorization",
          "MediaBrowser Client=Test, Device=Test, DeviceId=test, Version=1.0, Token=invalid");
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }
}
