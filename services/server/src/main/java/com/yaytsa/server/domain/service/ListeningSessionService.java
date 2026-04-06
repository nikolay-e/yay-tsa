package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.GenreRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class ListeningSessionService {

  private final ListeningSessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final GenreRepository genreRepository;
  private final ObjectMapper objectMapper;

  public ListeningSessionService(
      ListeningSessionRepository sessionRepository,
      UserRepository userRepository,
      GenreRepository genreRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.userRepository = userRepository;
    this.genreRepository = genreRepository;
    this.objectMapper = objectMapper;
  }

  public ListeningSessionEntity createSession(UUID userId, Map<String, Object> initialState) {
    return createSession(userId, initialState, null);
  }

  public ListeningSessionEntity createSession(
      UUID userId, Map<String, Object> initialState, UUID seedTrackId) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId));

    Map<String, Object> stateMap = initialState != null ? initialState : Map.of();

    ListeningSessionEntity session = new ListeningSessionEntity();
    session.setUser(user);
    session.setState(serializeJson(stateMap));
    applyTypedFields(session, stateMap);
    session.setSeedTrackId(seedTrackId);
    if (seedTrackId != null) {
      List<String> genres = genreRepository.findGenreNamesByItemId(seedTrackId);
      if (!genres.isEmpty()) {
        session.setSeedGenres(genres.toArray(String[]::new));
      }
    }
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

  @Transactional(readOnly = true)
  public ListeningSessionEntity findActiveSession(UUID userId) {
    return sessionRepository
        .findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(userId)
        .orElse(null);
  }

  public UUID getSessionOwnerId(UUID sessionId) {
    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));
    return session.getUser().getId();
  }

  public void verifyOwnership(UUID sessionId, AuthenticatedUser user) {
    UUID ownerId = getSessionOwnerId(sessionId);
    if (!user.getUserEntity().isAdmin() && !ownerId.equals(user.getUserEntity().getId())) {
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
    }
  }

  @Scheduled(fixedDelay = 3600_000)
  public void cleanupStaleSessions() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);
    List<ListeningSessionEntity> stale = sessionRepository.findStaleSessions(cutoff);
    if (!stale.isEmpty()) {
      OffsetDateTime now = OffsetDateTime.now();
      for (ListeningSessionEntity s : stale) {
        s.setEndedAt(now);
      }
      sessionRepository.saveAll(stale);
      log.info("Cleaned up {} stale DJ sessions (inactive >24h)", stale.size());
    }
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
