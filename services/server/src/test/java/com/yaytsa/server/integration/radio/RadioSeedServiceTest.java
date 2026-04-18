package com.yaytsa.server.integration.radio;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaytsa.server.domain.service.RadioSeedService;
import com.yaytsa.server.dto.response.RadioSeedsResponse;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.integration.Feature;
import com.yaytsa.server.integration.MockExternalServicesConfig;
import com.yaytsa.server.integration.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfig.class, MockExternalServicesConfig.class})
@ActiveProfiles("tc")
@DisplayName("Feature: Radio seed generation (FEAT-RADIO)")
class RadioSeedServiceTest {

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
  @Feature(id = "FEAT-RADIO", ac = "AC-01")
  @DisplayName("AC-01: Empty seeds for user with no play history")
  void emptyForUserWithNoHistory() {
    RadioSeedsResponse response = radioSeedService.getSeeds(userId);
    assertThat(response.seeds()).isEmpty();
  }

  @Test
  @Feature(id = "FEAT-RADIO", ac = "AC-02")
  @DisplayName("AC-02: Empty seeds for unknown user")
  void emptyForUnknownUser() {
    RadioSeedsResponse response = radioSeedService.getSeeds(UUID.randomUUID());
    assertThat(response.seeds()).isEmpty();
  }
}
