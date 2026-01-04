package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.SessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SessionService {

  private final SessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final PlayStateService playStateService;

  public SessionService(
      SessionRepository sessionRepository,
      UserRepository userRepository,
      ItemRepository itemRepository,
      AudioTrackRepository audioTrackRepository,
      PlayStateService playStateService) {
    this.sessionRepository = sessionRepository;
    this.userRepository = userRepository;
    this.itemRepository = itemRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.playStateService = playStateService;
  }

  public void reportPlaybackStart(UUID userId, String deviceId, String deviceName, UUID itemId) {
    SessionEntity session = findOrCreateSession(userId, deviceId, deviceName);
    ItemEntity item = itemRepository.findById(itemId).orElse(null);
    session.setNowPlayingItem(item);
    session.setPositionMs(0L);
    session.setPaused(false);
    session.setLastUpdate(OffsetDateTime.now());
    sessionRepository.save(session);

    if (item != null) {
      playStateService.incrementPlayCount(userId, itemId);

      if (item.getType() == ItemType.AudioTrack) {
        audioTrackRepository
            .findById(itemId)
            .ifPresent(
                track -> {
                  if (track.getAlbum() != null) {
                    playStateService.incrementPlayCount(userId, track.getAlbum().getId());
                  }
                });
      }
    }
  }

  public void reportPlaybackProgress(
      UUID userId,
      String deviceId,
      String deviceName,
      UUID itemId,
      long positionMs,
      boolean isPaused) {
    SessionEntity session = findOrCreateSession(userId, deviceId, deviceName);
    ItemEntity item = itemRepository.findById(itemId).orElse(null);
    session.setNowPlayingItem(item);
    session.setPositionMs(positionMs);
    session.setPaused(isPaused);
    session.setLastUpdate(OffsetDateTime.now());
    sessionRepository.save(session);
  }

  public void reportPlaybackStopped(UUID userId, String deviceId, UUID itemId, long positionMs) {
    Optional<SessionEntity> sessionOpt =
        sessionRepository.findByUserIdAndDeviceId(userId, deviceId);

    if (sessionOpt.isPresent()) {
      SessionEntity session = sessionOpt.get();
      session.setNowPlayingItem(null);
      session.setPositionMs(positionMs);
      session.setPaused(true);
      session.setLastUpdate(OffsetDateTime.now());
      sessionRepository.save(session);
    }
  }

  @Transactional(readOnly = true)
  public List<SessionEntity> getActiveSessions(UUID userId) {
    return sessionRepository.findAllByUserId(userId);
  }

  @Transactional(readOnly = true)
  public List<SessionEntity> getAllActiveSessions() {
    return sessionRepository.findAllWithUserAndItem();
  }

  @Transactional(readOnly = true)
  public Optional<SessionEntity> getSession(UUID sessionId) {
    return sessionRepository.findByIdWithUserAndItem(sessionId);
  }

  public void pingSession(UUID userId, String deviceId) {
    sessionRepository
        .findByUserIdAndDeviceId(userId, deviceId)
        .ifPresent(
            session -> {
              session.setLastUpdate(OffsetDateTime.now());
              sessionRepository.save(session);
            });
  }

  private SessionEntity findOrCreateSession(UUID userId, String deviceId, String deviceName) {
    return sessionRepository
        .findByUserIdAndDeviceId(userId, deviceId)
        .orElseGet(
            () -> {
              UserEntity user =
                  userRepository
                      .findById(userId)
                      .orElseThrow(() -> new RuntimeException("User not found: " + userId));

              SessionEntity newSession = new SessionEntity();
              newSession.setUser(user);
              newSession.setDeviceId(deviceId);
              newSession.setDeviceName(deviceName != null ? deviceName : "Unknown Device");
              newSession.setLastUpdate(OffsetDateTime.now());
              return newSession;
            });
  }
}
