package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AppSettingsEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AppSettingsRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing application-level settings stored in the database.
 *
 * <p>Priority order for values: DB → environment variable → empty string.
 */
@Service
public class AppSettingsService {

  private final AppSettingsRepository repository;

  public AppSettingsService(AppSettingsRepository repository) {
    this.repository = repository;
  }

  /**
   * Reads a setting value. DB takes priority over env var.
   *
   * @param key Setting key (e.g. "metadata.genius.token")
   * @param envVar Environment variable name to use as fallback (e.g. "GENIUS_ACCESS_TOKEN")
   * @return Setting value or empty string if not set anywhere
   */
  public String get(String key, String envVar) {
    return repository
        .findById(key)
        .map(AppSettingsEntity::getValue)
        .filter(v -> v != null && !v.isBlank())
        .orElseGet(
            () -> {
              if (envVar != null && !envVar.isBlank()) {
                String envValue = System.getenv(envVar);
                return envValue != null ? envValue : "";
              }
              return "";
            });
  }

  /**
   * Reads a setting value with no env var fallback.
   */
  public String get(String key) {
    return get(key, null);
  }

  /**
   * Stores or updates a setting value in the database.
   */
  @Transactional
  public void set(String key, String value) {
    AppSettingsEntity entity =
        repository.findById(key).orElse(new AppSettingsEntity(key, value));
    entity.setValue(value);
    entity.setUpdatedAt(OffsetDateTime.now());
    repository.save(entity);
  }
}
