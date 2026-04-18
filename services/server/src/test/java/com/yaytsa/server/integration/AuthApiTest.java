package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

@DisplayName("Feature: Authentication (FEAT-AUTH)")
@Tag("auth")
class AuthApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("ValidCredentials")
  class ValidCredentials {

    @Test
    @DisplayName("AC-01: Valid credentials return token and user info")
    @Feature(id = "FEAT-AUTH", ac = "AC-01")
    void validCredentialsReturnTokenAndUserInfo() throws Exception {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      String body = "{\"Username\":\"admin\",\"Pw\":\"admin123\"}";
      HttpEntity<String> request = new HttpEntity<>(body, headers);

      ResponseEntity<String> response =
          restTemplate.postForEntity("/Users/AuthenticateByName", request, String.class);

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
  @DisplayName("TokenRevocation")
  class TokenRevocation {

    private TestDataFactory.AuthResult freshAuth;

    @BeforeEach
    void loginFreshSession() {
      freshAuth = freshLogin();
    }

    @Test
    @DisplayName("AC-03: Token revoked after logout")
    @Feature(id = "FEAT-AUTH", ac = "AC-03")
    void tokenRevokedAfterLogout() {
      ResponseEntity<String> beforeLogout =
          restTemplate.exchange(
              "/Users/" + freshAuth.userId(),
              HttpMethod.GET,
              new HttpEntity<>(headersWithToken(freshAuth.rawToken())),
              String.class);
      assertEquals(HttpStatus.OK, beforeLogout.getStatusCode());

      ResponseEntity<String> logoutResponse =
          restTemplate.exchange(
              "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(headersWithToken(freshAuth.rawToken())),
              String.class);
      assertEquals(HttpStatus.NO_CONTENT, logoutResponse.getStatusCode());

      ResponseEntity<String> afterLogout =
          restTemplate.exchange(
              "/Users/" + freshAuth.userId(),
              HttpMethod.GET,
              new HttpEntity<>(headersWithToken(freshAuth.rawToken())),
              String.class);
      assertEquals(HttpStatus.UNAUTHORIZED, afterLogout.getStatusCode());
    }

    @Test
    @DisplayName("AC-04: Double logout returns 401")
    @Feature(id = "FEAT-AUTH", ac = "AC-04")
    void doubleLogoutReturns401() {
      ResponseEntity<String> firstLogout =
          restTemplate.exchange(
              "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(headersWithToken(freshAuth.rawToken())),
              String.class);
      assertEquals(HttpStatus.NO_CONTENT, firstLogout.getStatusCode());

      ResponseEntity<String> secondLogout =
          restTemplate.exchange(
              "/Sessions/Logout",
              HttpMethod.POST,
              new HttpEntity<>(headersWithToken(freshAuth.rawToken())),
              String.class);
      assertEquals(HttpStatus.UNAUTHORIZED, secondLogout.getStatusCode());
    }

    @Test
    @DisplayName("AC-05: api_key query param auth works")
    @Feature(id = "FEAT-AUTH", ac = "AC-05")
    void apiKeyQueryParamAuthWorks() {
      ResponseEntity<String> response =
          restTemplate.getForEntity(
              "/Users/" + freshAuth.userId() + "?api_key=" + freshAuth.rawToken(), String.class);
      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
  }
}
