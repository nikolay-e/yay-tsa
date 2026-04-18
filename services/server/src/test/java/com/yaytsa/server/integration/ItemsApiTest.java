package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Items API (FEAT-ITEMS)")
@Tag("items")
class ItemsApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("Pagination")
  class Pagination {

    @Test
    @DisplayName("AC-01: Paginated album listing")
    @Feature(id = "FEAT-ITEMS", ac = "AC-01")
    void getAlbumsWithPagination() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&StartIndex=0&Limit=10",
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
  @DisplayName("Search")
  class Search {

    @Test
    @DisplayName("AC-02: Search by term returns matching")
    @Feature(id = "FEAT-ITEMS", ac = "AC-02")
    void searchByTerm() throws Exception {
      String searchTerm = "Tes";

      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio&Recursive=true&SearchTerm=" + searchTerm,
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
    @DisplayName("AC-03: Nonsense search returns empty")
    @Feature(id = "FEAT-ITEMS", ac = "AC-03")
    void searchByNonsenseTerm() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio&Recursive=true&SearchTerm=zzzzxxxxxnonexistent99999",
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
  @DisplayName("BoundaryConditions")
  class BoundaryConditions {

    @Test
    @DisplayName("AC-04: Limit=0 returns 200")
    @Feature(id = "FEAT-ITEMS", ac = "AC-04")
    void getItemsWithZeroLimit() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
      assertTrue(json.has("Items"));
    }

    @Test
    @DisplayName("AC-05: Negative limit returns 200")
    @Feature(id = "FEAT-ITEMS", ac = "AC-05")
    void getItemsWithNegativeLimit() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=-1",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("AC-06: Unknown type returns 200")
    @Feature(id = "FEAT-ITEMS", ac = "AC-06")
    void getItemsWithUnknownType() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Video&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
    }

    @Test
    @DisplayName("AC-07: StartIndex beyond total")
    @Feature(id = "FEAT-ITEMS", ac = "AC-07")
    void getItemsWithStartIndexBeyondTotal() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio&Recursive=true&StartIndex=999999&Limit=10",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals(0, json.get("Items").size());
      assertTrue(json.get("TotalRecordCount").asInt() >= 0);
    }

    @Test
    @DisplayName("AC-08: Mixed valid/invalid types")
    @Feature(id = "FEAT-ITEMS", ac = "AC-08")
    void getItemsWithMixedTypes() throws Exception {
      HttpEntity<Void> request = new HttpEntity<>(authHeaders());
      ResponseEntity<String> response =
          restTemplate.exchange(
              "/Items?IncludeItemTypes=Audio,InvalidType&Recursive=true",
              HttpMethod.GET,
              request,
              String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      JsonNode json = objectMapper.readTree(response.getBody());
      assertTrue(json.has("TotalRecordCount"));
    }
  }
}
