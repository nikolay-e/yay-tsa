package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.AppSettingsEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AppSettingsRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

  private final AppSettingsRepository repository;

  public AppSettingsService(AppSettingsRepository repository) {
    this.repository = repository;
  }

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

  public String get(String key) {
    return get(key, null);
  }

  @Transactional
  public void set(String key, String value) {
    AppSettingsEntity entity = repository.findById(key).orElse(new AppSettingsEntity(key, value));
    entity.setValue(value);
    entity.setUpdatedAt(OffsetDateTime.now());
    repository.save(entity);
  }
}
