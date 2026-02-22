package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.http.*;

@DisplayName("Feature: Playlists API")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlaylistsApiTest extends BaseIntegrationTest {

  private static String createdPlaylistId;
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
  @DisplayName("Scenario: Get playlists")
  class GetPlaylists {

    @Test
    @DisplayName("Given: Authenticated user, When: GET /Playlists, Then: Returns playlists list")
    void getPlaylists() throws Exception {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(BASE_URL + "/Playlists", HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }
  }

  @Nested
  @DisplayName("Scenario: Create playlist")
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class CreatePlaylist {

    @Test
    @Order(1)
    @DisplayName("Given: Valid request, When: POST /Playlists, Then: Creates playlist")
    void createPlaylist() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(userId != null, "User ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = String.format("{\"Name\":\"Test Playlist\",\"UserId\":\"%s\"}", userId);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Playlists", request, String.class);

      assertTrue(
          response.getStatusCode() == HttpStatus.CREATED
              || response.getStatusCode() == HttpStatus.OK,
          "Expected 201 or 200 but got " + response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertNotNull(json.get("Id"));
      createdPlaylistId = json.get("Id").asText();
    }

    @Test
    @Order(2)
    @DisplayName("Given: Created playlist ID, When: GET /Playlists/{id}, Then: Returns playlist")
    void getPlaylistById() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(createdPlaylistId != null, "Playlist ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + createdPlaylistId, HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(createdPlaylistId, json.get("Id").asText());
    }
  }

  @Nested
  @DisplayName("Scenario: Playlist items")
  class PlaylistItems {

    @Test
    @DisplayName("Given: Valid playlist ID, When: GET /Playlists/{id}/Items, Then: Returns items")
    void getPlaylistItems() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(createdPlaylistId != null, "Playlist ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + createdPlaylistId + "/Items",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }

    @Test
    @DisplayName(
        "Given: Valid track ID, When: POST /Playlists/{id}/Items, Then: Adds item to playlist")
    void addItemToPlaylist() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(createdPlaylistId != null, "Playlist ID required");
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + createdPlaylistId + "/Items?Ids=" + firstTrackId,
              HttpMethod.POST,
              request,
              String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Update playlist")
  class UpdatePlaylist {

    @Test
    @DisplayName("Given: Valid playlist ID, When: PUT /Playlists/{id}, Then: Updates playlist")
    void updatePlaylist() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(createdPlaylistId != null, "Playlist ID required");

      HttpHeaders headers = authHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = "{\"Name\":\"Updated Playlist Name\"}";
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + createdPlaylistId, HttpMethod.PUT, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals("Updated Playlist Name", json.get("Name").asText());
    }
  }

  @Nested
  @DisplayName("Scenario: Delete playlist")
  class DeletePlaylist {

    @Test
    @DisplayName("Given: Valid playlist ID, When: DELETE /Playlists/{id}, Then: Deletes playlist")
    void deletePlaylist() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(createdPlaylistId != null, "Playlist ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + createdPlaylistId,
              HttpMethod.DELETE,
              request,
              String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Unauthorized access")
  class UnauthorizedAccess {

    @Test
    @DisplayName("Given: No auth token, When: GET /Playlists, Then: Returns 401")
    void getPlaylistsWithoutAuth() {
      ResponseEntity<String> response =
          restTemplate.getForEntity(BASE_URL + "/Playlists", String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }
}
