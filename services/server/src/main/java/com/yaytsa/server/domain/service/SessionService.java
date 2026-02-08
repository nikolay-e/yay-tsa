package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import com.yaytsa.server.infrastructure.persistence.entity.PlayHistoryEntity;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.SessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SessionService {

  private static final long SCROBBLE_DURATION_THRESHOLD_MS = 240_000L;
  private static final long MIN_TRACK_DURATION_FOR_SCROBBLE_MS = 30_000L;

  private final SessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final PlayStateService playStateService;
  private final PlayHistoryRepository playHistoryRepository;

  public SessionService(
      SessionRepository sessionRepository,
      UserRepository userRepository,
      ItemRepository itemRepository,
      AudioTrackRepository audioTrackRepository,
      PlayStateService playStateService,
      PlayHistoryRepository playHistoryRepository) {
    this.sessionRepository = sessionRepository;
    this.userRepository = userRepository;
    this.itemRepository = itemRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.playStateService = playStateService;
    this.playHistoryRepository = playHistoryRepository;
  }

  public void reportPlaybackStart(UUID userId, String deviceId, String deviceName, UUID itemId) {
    SessionEntity session = findOrCreateSession(userId, deviceId, deviceName);

    if (session.getNowPlayingItem() != null) {
      finalizePlayback(session, session.getPositionMs());
    }

    ItemEntity item = itemRepository.findById(itemId).orElse(null);
    session.setNowPlayingItem(item);
    session.setPositionMs(0L);
    session.setPaused(false);
    session.setPlaybackStartedAt(OffsetDateTime.now());
    session.setLastUpdate(OffsetDateTime.now());
    sessionRepository.save(session);
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

      if (session.getNowPlayingItem() != null) {
        finalizePlayback(session, positionMs);
      }

      session.setNowPlayingItem(null);
      session.setPositionMs(positionMs);
      session.setPaused(true);
      session.setPlaybackStartedAt(null);
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

  private void finalizePlayback(SessionEntity session, long positionMs) {
    ItemEntity item = session.getNowPlayingItem();
    if (item == null || item.getType() != ItemType.AudioTrack) {
      return;
    }

    Optional<AudioTrackEntity> trackOpt = audioTrackRepository.findById(item.getId());
    if (trackOpt.isEmpty()) {
      return;
    }

    AudioTrackEntity track = trackOpt.get();
    long durationMs = track.getDurationMs() != null ? track.getDurationMs() : 0L;
    if (durationMs <= 0) {
      return;
    }

    long playedMs = Math.max(0, positionMs);
    boolean scrobbled = isScrobble(playedMs, durationMs);
    boolean completed = durationMs > 0 && playedMs >= (durationMs * 95 / 100);
    boolean skipped = !scrobbled && !completed;

    PlayHistoryEntity history = new PlayHistoryEntity();
    history.setUser(session.getUser());
    history.setItem(item);
    history.setStartedAt(
        session.getPlaybackStartedAt() != null
            ? session.getPlaybackStartedAt()
            : session.getLastUpdate());
    history.setDurationMs(durationMs);
    history.setPlayedMs(playedMs);
    history.setCompleted(completed);
    history.setScrobbled(scrobbled);
    history.setSkipped(skipped);
    playHistoryRepository.save(history);

    if (scrobbled) {
      UUID userId = session.getUser().getId();
      playStateService.incrementPlayCount(userId, item.getId());

      if (track.getAlbum() != null) {
        playStateService.incrementPlayCount(userId, track.getAlbum().getId());
      }
    }
  }

  private boolean isScrobble(long playedMs, long durationMs) {
    if (durationMs < MIN_TRACK_DURATION_FOR_SCROBBLE_MS) {
      return false;
    }
    long threshold = Math.min(durationMs / 2, SCROBBLE_DURATION_THRESHOLD_MS);
    return playedMs >= threshold;
  }

  private SessionEntity findOrCreateSession(UUID userId, String deviceId, String deviceName) {
    return sessionRepository
        .findByUserIdAndDeviceId(userId, deviceId)
        .orElseGet(
            () -> {
              UserEntity user =
                  userRepository
                      .findById(userId)
                      .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId));

              SessionEntity newSession = new SessionEntity();
              newSession.setUser(user);
              newSession.setDeviceId(deviceId);
              newSession.setDeviceName(deviceName != null ? deviceName : "Unknown Device");
              newSession.setLastUpdate(OffsetDateTime.now());

              try {
                sessionRepository.saveAndFlush(newSession);
              } catch (DataIntegrityViolationException e) {
                return sessionRepository
                    .findByUserIdAndDeviceId(userId, deviceId)
                    .orElseThrow(() -> e);
              }
              return newSession;
            });
  }
}
