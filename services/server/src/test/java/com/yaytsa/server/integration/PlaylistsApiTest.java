package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Playlists API (FEAT-PLAYLISTS)")
@Tag("playlists")
class PlaylistsApiTest extends BaseIntegrationTest {

  private String createTestPlaylist(String name) throws Exception {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String body = String.format("{\"Name\":\"%s\",\"UserId\":\"%s\"}", name, userId);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity("/Playlists", request, String.class);

    assertTrue(
        response.getStatusCode() == HttpStatus.CREATED || response.getStatusCode() == HttpStatus.OK,
        "Expected 201 or 200 but got " + response.getStatusCode());

    JsonNode json = objectMapper.readTree(response.getBody());
    return json.get("Id").asText();
  }

  private void deletePlaylistQuietly(String playlistId) {
    try {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      restTemplate.exchange("/Playlists/" + playlistId, HttpMethod.DELETE, request, String.class);
    } catch (Exception ignored) {
    }
  }

  @Nested
  @DisplayName("CreateAndRetrieve")
  class CreateAndRetrieve {

    @Test
    @DisplayName("AC-01: Create playlist")
    @Feature(id = "FEAT-PLAYLISTS", ac = "AC-01")
    void createPlaylist() throws Exception {
      String playlistId = createTestPlaylist("Test Create");
      try {
        assertNotNull(playlistId);
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }

    @Test
    @DisplayName("AC-02: Get playlist by ID")
    @Feature(id = "FEAT-PLAYLISTS", ac = "AC-02")
    void getPlaylistById() throws Exception {
      String playlistId = createTestPlaylist("Test Get By Id");
      try {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<String> response =
            restTemplate.exchange(
                "/Playlists/" + playlistId, HttpMethod.GET, request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals(playlistId, json.get("Id").asText());
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }
  }

  @Nested
  @DisplayName("ItemManagement")
  class ItemManagement {

    @Test
    @DisplayName("AC-03: Add item to playlist")
    @Feature(id = "FEAT-PLAYLISTS", ac = "AC-03")
    void addItemToPlaylist() throws Exception {
      String playlistId = createTestPlaylist("Test Add Item");
      try {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<String> response =
            restTemplate.exchange(
                "/Playlists/" + playlistId + "/Items?Ids=" + testTrackId1,
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
  @DisplayName("Update")
  class Update {

    @Test
    @DisplayName("AC-04: Update playlist name")
    @Feature(id = "FEAT-PLAYLISTS", ac = "AC-04")
    void updatePlaylist() throws Exception {
      String playlistId = createTestPlaylist("Test Update");
      try {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"Name\":\"Updated Playlist Name\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response =
            restTemplate.exchange(
                "/Playlists/" + playlistId, HttpMethod.PUT, request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertEquals("Updated Playlist Name", json.get("Name").asText());
      } finally {
        deletePlaylistQuietly(playlistId);
      }
    }
  }

  @Nested
  @DisplayName("Delete")
  class Delete {

    @Test
    @DisplayName("AC-05: Delete playlist")
    @Feature(id = "FEAT-PLAYLISTS", ac = "AC-05")
    void deletePlaylist() throws Exception {
      String playlistId = createTestPlaylist("Test Delete");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Playlists/" + playlistId, HttpMethod.DELETE, request, String.class);

      assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
  }
}
