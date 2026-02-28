package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.persistence.repository.UserPreferenceContractRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserPreferencesService {

  private final UserPreferenceContractRepository preferencesRepository;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  public UserPreferencesService(
      UserPreferenceContractRepository preferencesRepository,
      UserRepository userRepository,
      ObjectMapper objectMapper) {
    this.preferencesRepository = preferencesRepository;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
  }

  public UserPreferenceContractEntity getPreferences(UUID userId) {
    return preferencesRepository.findById(userId).orElseGet(() -> createDefault(userId));
  }

  public UserPreferenceContractEntity updatePreferences(
      UUID userId,
      Map<String, Object> hardRules,
      Map<String, Object> softPrefs,
      Map<String, Object> djStyle,
      Object redLines) {

    UserPreferenceContractEntity preferences =
        preferencesRepository.findById(userId).orElseGet(() -> createDefault(userId));

    if (hardRules != null) {
      preferences.setHardRules(serializeJson(hardRules));
    }
    if (softPrefs != null) {
      preferences.setSoftPrefs(serializeJson(softPrefs));
    }
    if (djStyle != null) {
      preferences.setDjStyle(serializeJson(djStyle));
    }
    if (redLines != null) {
      preferences.setRedLines(serializeJson(redLines));
    }

    return preferencesRepository.save(preferences);
  }

  private UserPreferenceContractEntity createDefault(UUID userId) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId));

    UserPreferenceContractEntity entity = new UserPreferenceContractEntity();
    entity.setUser(user);
    return preferencesRepository.save(entity);
  }

  private String serializeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }
}
