package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Authentication API")
class AuthApiTest extends BaseIntegrationTest {

  private static final String AUTH_HEADER = "Authorization";
  private static final String AUTH_PREFIX = "Bearer ";

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

      String body = String.format("{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertNotNull(json.get("AccessToken"));
      assertFalse(json.get("AccessToken").asText().isEmpty());
      assertNotNull(json.get("User").get("Name"));
      assertFalse(json.get("User").get("Name").asText().isEmpty());
      assertNotNull(json.get("User").get("Id"));
    }
  }

  @Nested
  @DisplayName("Scenario: Token lifecycle — revocation and api_key auth")
  class TokenLifecycle {

    private String freshToken;
    private String freshUserId;

    @BeforeEach
    void loginFreshSession() throws Exception {
      String username = System.getenv().getOrDefault("YAYTSA_TEST_USERNAME", "admin");
      String password = System.getenv().getOrDefault("YAYTSA_TEST_PASSWORD", "admin123");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = String.format("{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      JsonNode json = objectMapper.readTree(response.getBody());
      freshToken = json.get("AccessToken").asText();
      freshUserId = json.get("User").get("Id").asText();
    }

    private HttpHeaders freshAuthHeaders() {
      HttpHeaders headers = new HttpHeaders();
      headers.set(AUTH_HEADER, AUTH_PREFIX + freshToken);
      return headers;
    }

    @Test
    @DisplayName(
        "Given: Valid token, When: Use token then logout then use token again,"
            + " Then: Token is rejected with 401 after logout")
    void tokenIsRejectedAfterLogout() {
      ResponseEntity<String> beforeLogout =
          restTemplate.exchange(
              BASE_URL + "/Users/" + freshUserId,
              HttpMethod.GET,
              new HttpEntity<>(freshAuthHeaders()),
              String.class);
      assertEquals(HttpStatus.OK, beforeLogout.getStatusCode());

      ResponseEntity<String> logoutResponse =
          restTemplate.exchange(
              BASE_URL + "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(freshAuthHeaders()),
              String.class);
      assertEquals(HttpStatus.NO_CONTENT, logoutResponse.getStatusCode());

      ResponseEntity<String> afterLogout =
          restTemplate.exchange(
              BASE_URL + "/Users/" + freshUserId,
              HttpMethod.GET,
              new HttpEntity<>(freshAuthHeaders()),
              String.class);
      assertEquals(HttpStatus.UNAUTHORIZED, afterLogout.getStatusCode());
    }

    @Test
    @DisplayName(
        "Given: Revoked token, When: Logout called again with same token,"
            + " Then: Returns 401 (token already invalidated)")
    void logoutWithRevokedTokenReturns401() {
      ResponseEntity<String> firstLogout =
          restTemplate.exchange(
              BASE_URL + "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(freshAuthHeaders()),
              String.class);
      assertEquals(HttpStatus.NO_CONTENT, firstLogout.getStatusCode());

      ResponseEntity<String> secondLogout =
          restTemplate.exchange(
              BASE_URL + "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(freshAuthHeaders()),
              String.class);
      assertEquals(HttpStatus.UNAUTHORIZED, secondLogout.getStatusCode());
    }

    @Test
    @DisplayName(
        "Given: Valid token, When: Request made with ?api_key=<token> query param,"
            + " Then: Returns 200 (api_key auth path works)")
    void apiKeyQueryParamAuthWorks() {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              BASE_URL + "/Users/" + freshUserId + "?api_key=" + freshToken, String.class);
      assertEquals(HttpStatus.OK, response.getStatusCode());
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

      String body = "{\"Username\":\"nonexistent\",\"Pw\":\"password\"}";
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

      assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
  }
}
