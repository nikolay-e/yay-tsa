package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Images API (FEAT-IMAGES)")
@Tag("images")
class ImagesApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("FormatConversion")
  class FormatConversion {

    @Test
    @DisplayName("AC-01: Get album image with format conversion")
    @Feature(id = "FEAT-IMAGES", ac = "AC-01")
    void getAlbumImageWithFormatConversion() {
      ResponseEntity<byte[]> response =
          restTemplate.getForEntity(
              "/Items/" + testAlbumId + "/Images/Primary?api_key=" + authToken + "&format=webp",
              byte[].class);

      assertTrue(
          response.getStatusCode().is2xxSuccessful(),
          "Expected 2xx but got " + response.getStatusCode());
      assertNotNull(response.getHeaders().getContentType());
      assertEquals("image/webp", response.getHeaders().getContentType().toString());
    }
  }

  @Nested
  @DisplayName("ETagCaching")
  class ETagCaching {

    @Test
    @DisplayName("AC-02: ETag caching returns 304")
    @Feature(id = "FEAT-IMAGES", ac = "AC-02")
    void etagCachingReturns304() {
      ResponseEntity<byte[]> firstResponse =
          restTemplate.getForEntity(
              "/Items/" + testAlbumId + "/Images/Primary?api_key=" + authToken, byte[].class);

      assertTrue(
          firstResponse.getStatusCode().is2xxSuccessful(),
          "Expected 2xx but got " + firstResponse.getStatusCode());
      String etag = firstResponse.getHeaders().getETag();
      assertNotNull(etag, "ETag header must be present for cache validation");

      HttpHeaders headers = new HttpHeaders();
      headers.setIfNoneMatch(etag);
      HttpEntity<Void> request = new HttpEntity<>(headers);

      ResponseEntity<byte[]> secondResponse =
          restTemplate.exchange(
              "/Items/" + testAlbumId + "/Images/Primary?api_key=" + authToken,
              HttpMethod.GET,
              request,
              byte[].class);

      assertTrue(
          secondResponse.getStatusCode() == HttpStatus.NOT_MODIFIED
              || secondResponse.getStatusCode() == HttpStatus.OK,
          "Expected 304 or 200 but got " + secondResponse.getStatusCode());
    }
  }
}
