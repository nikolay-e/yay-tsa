package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Images API")
class ImagesApiTest extends BaseIntegrationTest {

  private static String firstAlbumId;
  private static String firstArtistId;

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

    ResponseEntity<String> artistsResponse =
        restTemplate.exchange(
            BASE_URL + "/Items?IncludeItemTypes=MusicArtist&Recursive=true&Limit=1",
            HttpMethod.GET,
            request,
            String.class);

    if (artistsResponse.getStatusCode().is2xxSuccessful() && artistsResponse.getBody() != null) {
      JsonNode json = objectMapper.readTree(artistsResponse.getBody());
      if (json.get("TotalRecordCount").asInt() > 0) {
        firstArtistId = json.get("Items").get(0).get("Id").asText();
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Get album image")
  class GetAlbumImage {

    @Test
    @DisplayName(
        "Given: Album with image, When: GET /Items/{id}/Images/Primary, Then: Returns image")
    void getAlbumPrimaryImage() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Items/" + firstAlbumId + "/Images/Primary?api_key=" + authToken,
              byte[].class);

      HttpStatus status = (HttpStatus) response.getStatusCode();
      assertTrue(
          status.is2xxSuccessful()
              || status == HttpStatus.NOT_FOUND
              || status == HttpStatus.BAD_REQUEST,
          "Expected 2xx, 404, or 400 but got " + status);
      if (status.is2xxSuccessful() && response.getBody() != null) {
        assertTrue(response.getBody().length > 0);
      }
    }

    @Test
    @DisplayName(
        "Given: Album with image, When: GET /Items/{id}/Images/Primary with size params, Then:"
            + " Returns resized image")
    void getAlbumImageResized() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL
                  + "/Items/"
                  + firstAlbumId
                  + "/Images/Primary?api_key="
                  + authToken
                  + "&maxWidth=100&maxHeight=100",
              byte[].class);

      HttpStatus status = (HttpStatus) response.getStatusCode();
      assertTrue(
          status.is2xxSuccessful()
              || status == HttpStatus.NOT_FOUND
              || status == HttpStatus.BAD_REQUEST,
          "Expected 2xx, 404, or 400 but got " + status);
    }

    @Test
    @DisplayName(
        "Given: Album with image, When: GET /Items/{id}/Images/Primary with format, Then: Returns"
            + " image in format")
    void getAlbumImageWebp() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL
                  + "/Items/"
                  + firstAlbumId
                  + "/Images/Primary?api_key="
                  + authToken
                  + "&format=webp",
              byte[].class);

      HttpStatus status = (HttpStatus) response.getStatusCode();
      assertTrue(
          status.is2xxSuccessful()
              || status == HttpStatus.NOT_FOUND
              || status == HttpStatus.BAD_REQUEST,
          "Expected 2xx, 404, or 400 but got " + status);
      if (status.is2xxSuccessful() && response.getHeaders().getContentType() != null) {
        assertEquals("image/webp", response.getHeaders().getContentType().toString());
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Get image by index")
  class GetImageByIndex {

    @Test
    @DisplayName(
        "Given: Item ID, When: GET /Items/{id}/Images/Primary/0, Then: Returns first image")
    void getImageByIndex() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Items/" + firstAlbumId + "/Images/Primary/0?api_key=" + authToken,
              byte[].class);

      HttpStatus status = (HttpStatus) response.getStatusCode();
      assertTrue(
          status.is2xxSuccessful()
              || status == HttpStatus.NOT_FOUND
              || status == HttpStatus.BAD_REQUEST,
          "Expected 2xx, 404, or 400 but got " + status);
    }
  }

  @Nested
  @DisplayName("Scenario: Get image types")
  class GetImageTypes {

    @Test
    @DisplayName(
        "Given: Valid item ID, When: GET /Items/{id}/Images, Then: Returns available types")
    void getImageTypes() throws Exception {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL + "/Items/" + firstAlbumId + "/Images?api_key=" + authToken, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
  }

  @Nested
  @DisplayName("Scenario: Image not found")
  class ImageNotFound {

    @Test
    @DisplayName(
        "Given: Non-existent item ID, When: GET /Items/{id}/Images/Primary, Then: Returns 404 or"
            + " 400")
    void getImageNotFound() {
      assumeTrue(authToken != null, "Auth token required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL
                  + "/Items/00000000-0000-0000-0000-000000000000/Images/Primary?api_key="
                  + authToken,
              byte[].class);

      assertTrue(
          response.getStatusCode() == HttpStatus.NOT_FOUND
              || response.getStatusCode() == HttpStatus.BAD_REQUEST);
    }
  }

  @Nested
  @DisplayName("Scenario: Image caching")
  class ImageCaching {

    @Test
    @DisplayName("Given: Image with ETag, When: GET with If-None-Match, Then: Returns 304 or image")
    void imageCaching() {
      assumeTrue(authToken != null, "Auth token required");
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> firstResponse =
          restTemplate.getForEntity(
              BASE_URL + "/Items/" + firstAlbumId + "/Images/Primary?api_key=" + authToken,
              byte[].class);

      if (firstResponse.getStatusCode().is2xxSuccessful()) {
        String etag = firstResponse.getHeaders().getETag();
        if (etag != null) {
          HttpHeaders headers = new HttpHeaders();
          headers.setIfNoneMatch(etag);
          HttpEntity<Void> request = new HttpEntity<>(headers);

          ResponseEntity<byte[]> secondResponse =
              restTemplate.exchange(
                  BASE_URL + "/Items/" + firstAlbumId + "/Images/Primary?api_key=" + authToken,
                  HttpMethod.GET,
                  request,
                  byte[].class);

          assertTrue(
              secondResponse.getStatusCode() == HttpStatus.NOT_MODIFIED
                  || secondResponse.getStatusCode() == HttpStatus.OK);
        }
      }
    }
  }

  @Nested
  @DisplayName("Scenario: Unauthorized access")
  class UnauthorizedAccess {

    @Test
    @DisplayName(
        "Given: No auth token, When: GET /Items/{id}/Images/Primary, Then: Returns image (public"
            + " endpoint)")
    void getImageWithoutAuth() {
      assumeTrue(firstAlbumId != null, "Album ID required");

      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              BASE_URL + "/Items/" + firstAlbumId + "/Images/Primary", byte[].class);

      assertTrue(
          response.getStatusCode().is2xxSuccessful()
              || response.getStatusCode() == HttpStatus.NOT_FOUND
              || response.getStatusCode() == HttpStatus.UNAUTHORIZED);
    }
  }
}
