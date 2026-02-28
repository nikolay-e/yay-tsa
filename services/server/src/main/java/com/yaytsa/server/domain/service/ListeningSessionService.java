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

    ListeningSessionEntity session = new ListeningSessionEntity();
    session.setUser(user);
    session.setState(serializeJson(initialState != null ? initialState : Map.of()));
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

    session.setState(serializeJson(newState != null ? newState : Map.of()));
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

  private String serializeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON state", e);
    }
  }
}
