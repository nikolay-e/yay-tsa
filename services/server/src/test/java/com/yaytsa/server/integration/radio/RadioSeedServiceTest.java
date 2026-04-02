package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.RadioSeedService;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfig.class, RadioSeedServiceTest.MockClientConfig.class})
@ActiveProfiles("tc")
@DisplayName("RadioSeedService")
class RadioSeedServiceTest {

  @TestConfiguration
  static class MockClientConfig {
    @Bean
    @Primary
    RadioSeedClient testRadioSeedClient() {
      return new RadioSeedClient(
          org.springframework.web.client.RestClient.builder(), "http://localhost:19999");
    }
  }

  @Autowired private RadioSeedService radioSeedService;
  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.executeWithoutResult(
        status -> {
          UserEntity user = new UserEntity();
          user.setUsername("svctest_" + UUID.randomUUID().toString().substring(0, 8));
          user.setPasswordHash("$2a$10$dummyhash");
          user.setActive(true);
          user.setAdmin(false);
          em.persist(user);
          userId = user.getId();
          em.flush();
          em.clear();
        });
  }

  @Test
  @DisplayName("returns empty for user with no play history")
  void emptyForUserWithNoHistory() {
    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    assertThat(response.seeds()).isEmpty();
  }

  @Test
  @DisplayName("returns empty for unknown user")
  void emptyForUnknownUser() {
    RadioSeedsResponse response = radioSeedService.getSeeds(UUID.randomUUID());
    assertThat(response.seeds()).isEmpty();
  }
}
