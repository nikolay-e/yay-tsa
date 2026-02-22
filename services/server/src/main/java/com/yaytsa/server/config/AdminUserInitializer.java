package com.yaytsa.server.config;

import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminUserInitializer {

  private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Value("${ADMIN_USERNAME:}")
  private String adminUsername;

  @Value("${ADMIN_PASSWORD:}")
  private String adminPassword;

  public AdminUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void onApplicationReady() {
    if (adminUsername.isBlank() || adminPassword.isBlank()) {
      return;
    }

    if (adminPassword.length() < 8) {
      log.warn("ADMIN_PASSWORD must be at least 8 characters, skipping admin user creation");
      return;
    }

    if (userRepository.existsByUsername(adminUsername)) {
      log.info("Admin user '{}' already exists, skipping creation", adminUsername);
      return;
    }

    UserEntity admin = new UserEntity();
    admin.setUsername(adminUsername);
    admin.setPasswordHash(passwordEncoder.encode(adminPassword));
    admin.setDisplayName("Administrator");
    admin.setAdmin(true);
    admin.setActive(true);
    userRepository.save(admin);

    log.info("Created admin user '{}'", adminUsername);
  }
}
