package com.yaytsa.server.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Feature: System API (FEAT-SYSTEM)")
@Tag("system")
class SystemApiTest extends BaseIntegrationTest {

  @Autowired private org.springframework.core.env.Environment env;

  @Nested
  @DisplayName("PublicSystemInfo")
  class PublicSystemInfo {

    @Test
    @DisplayName("AC-01: Public system info returns server metadata")
    @Feature(id = "FEAT-SYSTEM", ac = "AC-01")
    void publicSystemInfoReturnsServerMetadata() throws Exception {
      ResponseEntity<String> response =
          restTemplate.getForEntity("/System/Info/Public", String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals("Yay-Tsa Media Server", json.get("ServerName").asText());
      assertEquals("Yay-Tsa", json.get("ProductName").asText());
      assertNotNull(json.get("Version").asText());
      assertNotNull(json.get("Id").asText());
      assertTrue(json.get("StartupWizardCompleted").asBoolean());
    }
  }

  @Nested
  @DisplayName("HealthCheck")
  class HealthCheck {

    @Test
    @DisplayName("AC-02: Health check returns UP")
    @Feature(id = "FEAT-SYSTEM", ac = "AC-02")
    void healthCheckReturnsUp() throws Exception {
      String mgmtPort = env.getProperty("local.management.port");
      TestRestTemplate mgmtClient =
          new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + mgmtPort));

      ResponseEntity<String> response = mgmtClient.getForEntity("/manage/health", String.class);

      assertEquals(HttpStatus.OK, response.getStatusCode());

      JsonNode json = objectMapper.readTree(response.getBody());
      assertEquals("UP", json.get("status").asText());
    }
  }
}
