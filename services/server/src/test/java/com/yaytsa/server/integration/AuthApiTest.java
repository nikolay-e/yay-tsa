package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Authentication API")
class AuthApiTest extends BaseIntegrationTest {

  private static final String AUTH_HEADER = "X-Emby-Authorization";
  private static final String AUTH_VALUE =
      "MediaBrowser Client=TestClient, Device=TestDevice, DeviceId=test-123, Version=1.0";

  @Nested
  @DisplayName("Scenario: User authenticates with valid credentials")
  class ValidAuthentication {

    @Test
    @DisplayName(
        "Given: Valid credentials, When: POST /Users/AuthenticateByName, Then: Returns token and"
            + " user info")
    void authenticateWithValidCredentials() throws Exception {
      String username = System.getenv().getOrDefault("YAYTSA_TEST_USERNAME", "admin");
      String password = System.getenv().getOrDefault("YAYTSA_TEST_PASSWORD", "admin123");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set(AUTH_HEADER, AUTH_VALUE);

      String body = String.format("{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertNotNull(json.get("AccessToken"));
      assertFalse(json.get("AccessToken").asText().isEmpty());
      assertEquals(username, json.get("User").get("Name").asText());
      assertNotNull(json.get("User").get("Id"));
    }
  }

  @Nested
  @DisplayName("Scenario: User authenticates with invalid credentials")
  class InvalidAuthentication {

    @Test
    @DisplayName("Given: Wrong password, When: POST /Users/AuthenticateByName, Then: Returns 401")
    void authenticateWithWrongPassword() {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set(AUTH_HEADER, AUTH_VALUE);

      String username = System.getenv().getOrDefault("YAYTSA_TEST_USERNAME", "admin");
      String body = String.format("{\"Username\":\"%s\",\"Pw\":\"wrongpassword\"}", username);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName(
        "Given: Non-existent user, When: POST /Users/AuthenticateByName, Then: Returns 401")
    void authenticateWithNonExistentUser() {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set(AUTH_HEADER, AUTH_VALUE);

      String body = "{\"Username\":\"nonexistent\",\"Pw\":\"password\"}";
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }
}
