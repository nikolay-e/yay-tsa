package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Users API")
class UsersApiTest extends BaseIntegrationTest {

  private static String firstAlbumId;
  private static String firstTrackId;

  @BeforeAll
  static void findTestData() throws Exception {
    HttpEntity<Void> request = new HttpEntity<>(authHeaders());

    ResponseEntity<String> albumsResponse =
        restTemplate.exchange(
            BASE_URL + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&Limit=1",
            HttpMethod.GET,
            request,
            String.class);

    if (albumsResponse.getStatusCode().is2xxSuccessful() && albumsResponse.getBody() != null) {
      JsonNode json = objectMapper.readTree(albumsResponse.getBody());
      if (json.get("TotalRecordCount").asInt() > 0) {
        firstAlbumId = json.get("Items").get(0).get("Id").asText();
      }
    }

    ResponseEntity<String> tracksResponse =
        restTemplate.exchange(
            BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1",
            HttpMethod.GET,
            request,
            String.class);

    if (tracksResponse.getStatusCode().is2xxSuccessful() && tracksResponse.getBody() != null) {
      JsonNode json = objectMapper.readTree(tracksResponse.getBody());
      if (json.get("TotalRecordCount").asInt() > 0) {
        firstTrackId = json.get("Items").get(0).get("Id").asText();
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Get user by ID")
  class GetUserById {

    @Test
    @DisplayName("Given: Valid user ID, When: GET /Users/{userId}, Then: Returns user info")
    void getUserById() throws Exception {
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId, HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(userId, json.get("Id").asText());
      assertNotNull(json.get("Name"));
    }
  }

  @Nested
  @DisplayName("Scenario: Get user items")
  class GetUserItems {

    @Test
    @DisplayName(
        "Given: Valid user ID with pagination, When: GET /Users/{userId}/Items, Then: Returns"
            + " paginated results")
    void getUserItemsWithPagination() throws Exception {
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL
                  + "/Users/"
                  + userId
                  + "/Items?IncludeItemTypes=Audio&Recursive=true&StartIndex=0&Limit=5",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(0, json.get("StartIndex").asInt());
      assertTrue(json.get("Items").size() <= 5);
    }
  }

  @Nested
  @DisplayName("Scenario: Get single user item")
  class GetSingleUserItem {

    @Test
    @DisplayName(
        "Given: Valid item ID, When: GET /Users/{userId}/Items/{itemId}, Then: Returns item with"
            + " user data")
    void getUserItem() throws Exception {
      assumeTrue(userId != null, "User ID required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/Items/" + firstAlbumId,
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(firstAlbumId, json.get("Id").asText());
      assertNotNull(json.get("Name"));
      assertNotNull(json.get("Type"));
    }
  }

  @Nested
  @DisplayName("Scenario: Favorite toggle")
  class FavoriteToggle {

    @Test
    @DisplayName(
        "Given: Valid track, When: POST favorite then DELETE unfavorite, Then: Item appears and"
            + " disappears from favorites")
    void toggleFavorite() throws Exception {
      assumeTrue(userId != null, "User ID required");
      assumeTrue(firstTrackId != null, "Track ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());

      ResponseEntity<String> markResponse =
          restTemplate.postForEntity(
              BASE_URL + "/Items/" + firstTrackId + "/Favorite?userId=" + userId,
              request,
              String.class);

      assertEquals(HttpStatus.OK, markResponse.getStatusCode());

      ResponseEntity<String> favoritesResponse =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/FavoriteItems?IncludeItemTypes=Audio&Limit=200",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, favoritesResponse.getStatusCode());
      JsonNode favoritesJson = objectMapper.readTree(favoritesResponse.getBody());
      boolean found = false;
      for (JsonNode item : favoritesJson.get("Items")) {
        if (firstTrackId.equals(item.get("Id").asText())) {
          found = true;
          break;
        }
      }
      assertTrue(found, "Track should appear in favorites after marking");

      ResponseEntity<String> unmarkResponse =
          restTemplate.exchange(
              BASE_URL + "/Items/" + firstTrackId + "/Favorite?userId=" + userId,
              HttpMethod.DELETE,
              request,
              String.class);

      assertEquals(HttpStatus.OK, unmarkResponse.getStatusCode());

      ResponseEntity<String> afterUnmarkResponse =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/FavoriteItems?IncludeItemTypes=Audio&Limit=200",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, afterUnmarkResponse.getStatusCode());
      JsonNode afterJson = objectMapper.readTree(afterUnmarkResponse.getBody());
      boolean stillFound = false;
      for (JsonNode item : afterJson.get("Items")) {
        if (firstTrackId.equals(item.get("Id").asText())) {
          stillFound = true;
          break;
        }
      }
      assertFalse(stillFound, "Track should not appear in favorites after unmarking");
    }
  }
}
