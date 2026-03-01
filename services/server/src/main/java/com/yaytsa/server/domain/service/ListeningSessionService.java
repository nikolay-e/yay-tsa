package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ListeningSessionService {

  private final ListeningSessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  public ListeningSessionService(
      ListeningSessionRepository sessionRepository,
      UserRepository userRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
  }

  public ListeningSessionEntity createSession(UUID userId, Map<String, Object> initialState) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId));

    Map<String, Object> stateMap = initialState != null ? initialState : Map.of();

    ListeningSessionEntity session = new ListeningSessionEntity();
    session.setUser(user);
    session.setState(serializeJson(stateMap));
    applyTypedFields(session, stateMap);
    session.setStartedAt(OffsetDateTime.now());
    session.setLastActivityAt(OffsetDateTime.now());

    return sessionRepository.save(session);
  }

  public ListeningSessionEntity updateState(UUID sessionId, Map<String, Object> newState) {
    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));

    if (session.getEndedAt() != null) {
      throw new IllegalStateException("Cannot update an ended session");
    }

    Map<String, Object> stateMap = newState != null ? newState : Map.of();
    session.setState(serializeJson(stateMap));
    applyTypedFields(session, stateMap);
    session.setLastActivityAt(OffsetDateTime.now());

    return sessionRepository.save(session);
  }

  public ListeningSessionEntity endSession(UUID sessionId) {
    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));

    session.setEndedAt(OffsetDateTime.now());
    session.setLastActivityAt(OffsetDateTime.now());

    return sessionRepository.save(session);
  }

  @Transactional(readOnly = true)
  public ListeningSessionEntity getSession(UUID sessionId) {
    return sessionRepository
        .findById(sessionId)
        .orElseThrow(() -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));
  }

  public UUID getSessionOwnerId(UUID sessionId) {
    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));
    return session.getUser().getId();
  }

  private void applyTypedFields(ListeningSessionEntity session, Map<String, Object> stateMap) {
    session.setEnergy(extractFloat(stateMap, "energy"));
    session.setIntensity(extractFloat(stateMap, "intensity"));
    session.setMoodTags(extractStringArray(stateMap, "moodTags"));
    session.setAttentionMode(extractString(stateMap, "attentionMode", "active"));
  }

  private Float extractFloat(Map<String, Object> map, String key) {
    Object val = map.get(key);
    return val instanceof Number n ? n.floatValue() : null;
  }

  private String[] extractStringArray(Map<String, Object> map, String key) {
    Object val = map.get(key);
    if (val instanceof List<?> list) {
      return list.stream().map(Object::toString).toArray(String[]::new);
    }
    return null;
  }

  private String extractString(Map<String, Object> map, String key, String defaultVal) {
    Object val = map.get(key);
    return val instanceof String s ? s : defaultVal;
  }

  private String serializeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON state", e);
    }
  }
}
