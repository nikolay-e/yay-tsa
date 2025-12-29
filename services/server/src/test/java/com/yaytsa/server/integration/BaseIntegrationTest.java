package com.yaytsa.server.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public abstract class BaseIntegrationTest {

  protected static final String BASE_URL;
  protected static final ObjectMapper objectMapper = new ObjectMapper();
  protected static TestRestTemplate restTemplate = new TestRestTemplate();
  protected static String authToken;
  protected static String userId;

  static {
    String host = System.getenv().getOrDefault("YAYTSA_SERVER_URL", "http://localhost:8096");
    BASE_URL = host;
  }

  @BeforeAll
  static void authenticate() throws Exception {
    String username = System.getenv().getOrDefault("YAYTSA_TEST_USERNAME", "admin");
    String password = System.getenv().getOrDefault("YAYTSA_TEST_PASSWORD", "admin123");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(
        "X-Emby-Authorization",
        "MediaBrowser Client=TestClient, Device=TestDevice, DeviceId=test-123, Version=1.0");

    String body = String.format("{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);
    HttpEntity<String> request = new HttpEntity<>(body, headers);

    ResponseEntity<String> response =
        restTemplate.postForEntity(BASE_URL + "/Users/AuthenticateByName", request, String.class);

    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
      JsonNode json = objectMapper.readTree(response.getBody());
      authToken = json.get("AccessToken").asText();
      userId = json.get("User").get("Id").asText();
    }
  }

  protected static HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(
        "X-Emby-Authorization",
        "MediaBrowser Client=TestClient, Device=TestDevice, DeviceId=test-123, Version=1.0, Token="
            + authToken);
    return headers;
  }
}
