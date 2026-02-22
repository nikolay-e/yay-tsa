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
    if (authToken == null) return;

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
      assumeTrue(authToken != null, "Auth token required");
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

    @Test
    @DisplayName("Given: Invalid user ID, When: GET /Users/{userId}, Then: Returns 404 or 500")
    void getUserByIdNotFound() {
      assumeTrue(authToken != null, "Auth token required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/00000000-0000-0000-0000-000000000000",
              HttpMethod.GET,
              request,
              String.class);

      assertTrue(
          response.getStatusCode() == HttpStatus.NOT_FOUND
              || response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Nested
  @DisplayName("Scenario: Get user items")
  class GetUserItems {

    @Test
    @DisplayName("Given: Valid user ID, When: GET /Users/{userId}/Items, Then: Returns items list")
    void getUserItems() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true",
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
        "Given: Valid user ID with pagination, When: GET /Users/{userId}/Items, Then: Returns"
            + " paginated results")
    void getUserItemsWithPagination() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
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
      assumeTrue(authToken != null, "Auth token required");
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

    @Test
    @DisplayName(
        "Given: Invalid item ID, When: GET /Users/{userId}/Items/{itemId}, Then: Returns 500")
    void getUserItemNotFound() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/Items/00000000-0000-0000-0000-000000000000",
              HttpMethod.GET,
              request,
              String.class);

      assertTrue(
          response.getStatusCode().is4xxClientError()
              || response.getStatusCode().is5xxServerError());
    }
  }

  @Nested
  @DisplayName("Scenario: Get user favorites")
  class GetUserFavorites {

    @Test
    @DisplayName(
        "Given: Valid user ID, When: GET /Users/{userId}/FavoriteItems, Then: Returns favorites"
            + " list")
    void getUserFavorites() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/FavoriteItems",
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
  @DisplayName("Scenario: Get resume items")
  class GetResumeItems {

    @Test
    @DisplayName(
        "Given: Valid user ID, When: GET /Users/{userId}/Items/Resume, Then: Returns recently"
            + " played")
    void getResumeItems() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(userId != null, "User ID required");

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Users/" + userId + "/Items/Resume",
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
    @DisplayName("Given: No auth token, When: GET /Users/{userId}, Then: Returns 401")
    void getUserWithoutAuth() {
      assumeTrue(userId != null, "User ID required");

      ResponseEntity<String> response =
          restTemplate.getForEntity(BASE_URL + "/Users/" + userId, String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }
}
