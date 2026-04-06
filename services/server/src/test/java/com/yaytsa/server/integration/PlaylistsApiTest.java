package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.http.*;

@DisplayName("Feature: Playlists API")
class PlaylistsApiTest extends BaseIntegrationTest {

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

  private static String createTestPlaylist(String name) throws Exception {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String body = String.format("{\"Name\":\"%s\",\"UserId\":\"%s\"}", name, userId);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity(BASE_URL + "/Playlists", request, String.class);

    assertTrue(
        response.getStatusCode() == HttpStatus.CREATED || response.getStatusCode() == HttpStatus.OK,
        "Expected 201 or 200 but got " + response.getStatusCode());

    JsonNode json = objectMapper.readTree(response.getBody());
    return json.get("Id").asText();
  }

  private static void deletePlaylistQuietly(String playlistId) {
    try {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      restTemplate.exchange(
          BASE_URL + "/Playlists/" + playlistId, HttpMethod.DELETE, request, String.class);
    } catch (Exception ignored) {
    }
  }

  @Nested
  @DisplayName("Scenario: Get playlists")
  class GetPlaylists {

    @Test
    @DisplayName("Given: Authenticated user, When: GET /Playlists, Then: Returns playlists list")
    void getPlaylists() throws Exception {
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
  @DisplayName("Scenario: Create and retrieve playlist")
  class CreatePlaylist {

    @Test
    @DisplayName("Given: Valid request, When: POST /Playlists, Then: Creates playlist")
    void createPlaylist() throws Exception {
      assumeTrue(userId != null, "User ID required");

      String playlistId = createTestPlaylist("Test Create");
      try {
        assertNotNull(playlistId);
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }

    @Test
    @DisplayName("Given: Created playlist, When: GET /Playlists/{id}, Then: Returns playlist")
    void getPlaylistById() throws Exception {
      assumeTrue(userId != null, "User ID required");

      String playlistId = createTestPlaylist("Test Get By Id");
      try {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<String> response =
            restTemplate.exchange(
                BASE_URL + "/Playlists/" + playlistId, HttpMethod.GET, request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(playlistId, json.get("Id").asText());
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Playlist items")
  class PlaylistItems {

    @Test
    @DisplayName("Given: Valid playlist ID, When: GET /Playlists/{id}/Items, Then: Returns items")
    void getPlaylistItems() throws Exception {
      assumeTrue(userId != null, "User ID required");

      String playlistId = createTestPlaylist("Test Get Items");
      try {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<String> response =
            restTemplate.exchange(
                BASE_URL + "/Playlists/" + playlistId + "/Items",
                HttpMethod.GET,
                request,
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertTrue(json.has("TotalRecordCount"));
        assertTrue(json.has("Items"));
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }

    @Test
    @DisplayName(
        "Given: Valid track ID, When: POST /Playlists/{id}/Items, Then: Adds item to playlist")
    void addItemToPlaylist() throws Exception {
      assumeTrue(userId != null, "User ID required");
      assumeTrue(firstTrackId != null, "Track ID required");

      String playlistId = createTestPlaylist("Test Add Item");
      try {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<String> response =
            restTemplate.exchange(
                BASE_URL + "/Playlists/" + playlistId + "/Items?Ids=" + firstTrackId,
                HttpMethod.POST,
                request,
                String.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Update playlist")
  class UpdatePlaylist {

    @Test
    @DisplayName("Given: Valid playlist ID, When: PUT /Playlists/{id}, Then: Updates playlist")
    void updatePlaylist() throws Exception {
      assumeTrue(userId != null, "User ID required");

      String playlistId = createTestPlaylist("Test Update");
      try {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"Name\":\"Updated Playlist Name\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response =
            restTemplate.exchange(
                BASE_URL + "/Playlists/" + playlistId, HttpMethod.PUT, request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("Updated Playlist Name", json.get("Name").asText());
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Delete playlist")
  class DeletePlaylist {

    @Test
    @DisplayName("Given: Valid playlist ID, When: DELETE /Playlists/{id}, Then: Deletes playlist")
    void deletePlaylist() throws Exception {
      assumeTrue(userId != null, "User ID required");

      String playlistId = createTestPlaylist("Test Delete");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Playlists/" + playlistId, HttpMethod.DELETE, request, String.class);

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
