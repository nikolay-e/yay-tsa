package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Items API")
class ItemsApiTest extends BaseIntegrationTest {

  private static String firstTrackName;

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
        firstTrackName = json.get("Items").get(0).get("Name").asText();
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Get music albums")
  class GetAlbums {

    @Test
    @DisplayName(
        "Given: Authenticated user, When: GET /Items with MusicAlbum filter, Then: Returns albums"
            + " list")
    void getAlbums() throws Exception {
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
  @DisplayName("Scenario: Search items")
  class SearchItems {

    @Test
    @DisplayName(
        "Given: Known track name, When: GET /Items with SearchTerm, Then: Returns matching results")
    void searchByTerm() throws Exception {
      assumeTrue(firstTrackName != null, "Track name required");

      String searchTerm =
          firstTrackName.length() > 3 ? firstTrackName.substring(0, 3) : firstTrackName;

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&SearchTerm=" + searchTerm,
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
      assertTrue(
          json.get("TotalRecordCount").asInt() > 0, "Search should return at least one result");
    }

    @Test
    @DisplayName(
        "Given: Nonsense search term, When: GET /Items with SearchTerm, Then: Returns empty"
            + " results")
    void searchByNonsenseTerm() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL
                  + "/Items?IncludeItemTypes=Audio&Recursive=true&SearchTerm=zzzzxxxxxnonexistent99999",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(0, json.get("TotalRecordCount").asInt());
      assertEquals(0, json.get("Items").size());
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
      headers.set("Authorization", "Bearer invalid");
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

  @Nested
  @DisplayName("Scenario: Edge cases and boundary conditions")
  class EdgeCases {

    @Test
    @DisplayName("Given: Limit=0, When: GET /Items, Then: Returns 200 with results (not 500)")
    void getItemsWithZeroLimit() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }

    @Test
    @DisplayName("Given: Negative limit, When: GET /Items, Then: Returns 200 (not 500)")
    void getItemsWithNegativeLimit() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=-1",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName(
        "Given: Unknown IncludeItemTypes, When: GET /Items, Then: Returns 200 with empty or all"
            + " results (not 500)")
    void getItemsWithUnknownType() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Video&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
    }

    @Test
    @DisplayName(
        "Given: StartIndex beyond total, When: GET /Items, Then: Returns empty items with valid"
            + " TotalRecordCount")
    void getItemsWithStartIndexBeyondTotal() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio&Recursive=true&StartIndex=999999&Limit=10",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(0, json.get("Items").size());
      assertTrue(json.get("TotalRecordCount").asInt() >= 0);
    }

    @Test
    @DisplayName(
        "Given: Mixed valid and invalid IncludeItemTypes, When: GET /Items, Then: Returns results"
            + " for valid types only")
    void getItemsWithMixedTypes() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              BASE_URL + "/Items?IncludeItemTypes=Audio,InvalidType&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
    }
  }
}
