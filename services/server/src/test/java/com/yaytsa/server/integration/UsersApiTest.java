package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Users API (FEAT-USERS)")
@Tag("users")
class UsersApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("UserProfile")
  class UserProfile {

    @Test
    @DisplayName("AC-01: Get user by ID returns profile")
    @Feature(id = "FEAT-USERS", ac = "AC-01")
    void getUserById() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange("/Users/" + userId, HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(userId, json.get("Id").asText());
      assertNotNull(json.get("Name"));
    }
  }

  @Nested
  @DisplayName("UserItems")
  class UserItems {

    @Test
    @DisplayName("AC-02: Paginated user items listing")
    @Feature(id = "FEAT-USERS", ac = "AC-02")
    void getUserItemsWithPagination() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Users/"
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
  @DisplayName("SingleItem")
  class SingleItem {

    @Test
    @DisplayName("AC-03: Get single user item by ID")
    @Feature(id = "FEAT-USERS", ac = "AC-03")
    void getUserItem() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Users/" + userId + "/Items/" + testAlbumId, HttpMethod.GET, request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(testAlbumId, json.get("Id").asText());
      assertNotNull(json.get("Name"));
      assertNotNull(json.get("Type"));
    }
  }

  @Nested
  @DisplayName("Favorites")
  class Favorites {

    @Test
    @DisplayName("AC-04: Mark and unmark favorite toggles correctly")
    @Feature(id = "FEAT-USERS", ac = "AC-04")
    void toggleFavorite() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());

      ResponseEntity<String> markResponse =
          restTemplate.postForEntity(
              "/Items/" + testTrackId1 + "/Favorite?userId=" + userId, request, String.class);

      assertEquals(HttpStatus.OK, markResponse.getStatusCode());

      ResponseEntity<String> favoritesResponse =
          restTemplate.exchange(
              "/Users/" + userId + "/FavoriteItems?IncludeItemTypes=Audio&Limit=200",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, favoritesResponse.getStatusCode());
      JsonNode favoritesJson = objectMapper.readTree(favoritesResponse.getBody());
      boolean found = false;
      for (JsonNode item : favoritesJson.get("Items")) {
        if (testTrackId1.equals(item.get("Id").asText())) {
          found = true;
          break;
        }
      }
      assertTrue(found, "Track should appear in favorites after marking");

      ResponseEntity<String> unmarkResponse =
          restTemplate.exchange(
              "/Items/" + testTrackId1 + "/Favorite?userId=" + userId,
              HttpMethod.DELETE,
              request,
              String.class);

      assertEquals(HttpStatus.OK, unmarkResponse.getStatusCode());

      ResponseEntity<String> afterUnmarkResponse =
          restTemplate.exchange(
              "/Users/" + userId + "/FavoriteItems?IncludeItemTypes=Audio&Limit=200",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, afterUnmarkResponse.getStatusCode());
      JsonNode afterJson = objectMapper.readTree(afterUnmarkResponse.getBody());
      boolean stillFound = false;
      for (JsonNode item : afterJson.get("Items")) {
        if (testTrackId1.equals(item.get("Id").asText())) {
          stillFound = true;
          break;
        }
      }
      assertFalse(stillFound, "Track should not appear in favorites after unmarking");
    }
  }
}
