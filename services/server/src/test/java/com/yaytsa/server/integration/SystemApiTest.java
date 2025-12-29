package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Feature: System API")
class SystemApiTest extends BaseIntegrationTest {

  @Nested
  @DisplayName("Scenario: Get public system info")
  class PublicSystemInfo {

    @Test
    @DisplayName("Given: Public endpoint, When: GET /System/Info/Public, Then: Returns server info")
    void getPublicSystemInfo() throws Exception {
      ResponseEntity<String> response =
          restTemplate.getForEntity(BASE_URL + "/System/Info/Public", String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals("Yaytsa Media Server", json.get("ServerName").asText());
      assertEquals("Yaytsa", json.get("ProductName").asText());
      assertNotNull(json.get("Version").asText());
      assertNotNull(json.get("Id").asText());
      assertTrue(json.get("StartupWizardCompleted").asBoolean());
    }
  }

  @Nested
  @DisplayName("Scenario: Health check endpoints")
  class HealthCheck {

    @Test
    @DisplayName("Given: Actuator enabled, When: GET /manage/health, Then: Returns UP status")
    void healthCheck() throws Exception {
      ResponseEntity<String> response =
          restTemplate.getForEntity(BASE_URL + "/manage/health", String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals("UP", json.get("status").asText());
    }
  }
}
