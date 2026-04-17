package com.yaytsa.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.SessionRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class DevicePresenceService {

  private static final long OFFLINE_TIMEOUT_SECONDS = 90;
  private static final int COMMAND_BUFFER_MAX = 20;

  private final SessionRepository sessionRepository;
  private final DeviceSseService deviceSseService;

  private final Cache<String, Queue<Object>> pendingCommands =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(1000).build();

  public DevicePresenceService(
      SessionRepository sessionRepository, DeviceSseService deviceSseService) {
    this.sessionRepository = sessionRepository;
    this.deviceSseService = deviceSseService;
  }

  public List<Map<String, Object>> listDevices(UUID userId) {
    return sessionRepository.findAllByUserIdWithItem(userId).stream()
        .map(DevicePresenceService::sessionToDeviceMap)
        .toList();
  }

  @Transactional
  public void heartbeat(UUID userId, String deviceId) {
    sessionRepository
        .findByUserIdAndDeviceId(userId, deviceId)
        .ifPresent(
            session -> {
              session.setOnline(true);
              session.setLastHeartbeatAt(OffsetDateTime.now());
              sessionRepository.save(session);
            });
  }

  public boolean sendCommand(UUID targetSessionId, UUID requestingUserId, Object command) {
    var session = sessionRepository.findByIdWithUserAndItem(targetSessionId).orElse(null);
    if (session == null) return false;
    if (!session.getUser().getId().equals(requestingUserId)) return false;

    String deviceKey = DeviceSseService.deviceKey(session.getUser().getId(), session.getDeviceId());

    boolean delivered = deviceSseService.sendCommand(deviceKey, command);
    if (!delivered) {
      bufferCommand(deviceKey, command);
    }
    return true;
  }

  public void drainPendingCommands(String deviceKey, DeviceSseService sseService) {
    Queue<Object> queue = pendingCommands.getIfPresent(deviceKey);
    if (queue == null) return;
    Object cmd;
    while ((cmd = queue.poll()) != null) {
      sseService.sendCommand(deviceKey, cmd);
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> buildTransferPayload(UUID sourceSessionId, UUID requestingUserId) {
    var source = sessionRepository.findByIdWithUserAndItem(sourceSessionId).orElse(null);
    if (source == null) return null;
    if (!source.getUser().getId().equals(requestingUserId)) return null;

    Map<String, Object> payload = new LinkedHashMap<>();
    if (source.getNowPlayingItem() != null) {
      payload.put("trackId", source.getNowPlayingItem().getId().toString());
      payload.put("trackName", source.getNowPlayingItem().getName());
    }
    payload.put("positionMs", source.getPositionMs());
    payload.put("paused", source.getPaused());
    payload.put("volumeLevel", source.getVolumeLevel());
    payload.put("sourceDeviceId", source.getDeviceId());
    payload.put("sourceSessionId", source.getId().toString());

    String sourceKey = DeviceSseService.deviceKey(source.getUser().getId(), source.getDeviceId());
    deviceSseService.sendCommand(sourceKey, Map.of("type", "PAUSE"));

    return payload;
  }

  @Scheduled(fixedRate = 30000)
  @Transactional
  public void markOfflineDevices() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(OFFLINE_TIMEOUT_SECONDS);

    var goingOffline =
        sessionRepository.findAll().stream()
            .filter(
                s ->
                    s.isOnline()
                        && s.getLastHeartbeatAt() != null
                        && s.getLastHeartbeatAt().isBefore(cutoff))
            .toList();

    if (goingOffline.isEmpty()) return;

    for (SessionEntity session : goingOffline) {
      session.setOnline(false);
      sessionRepository.save(session);
      deviceSseService.broadcastToUser(
          session.getUser().getId(),
          "device_offline",
          Map.of(
              "sessionId", session.getId().toString(),
              "deviceId", session.getDeviceId()));
    }

    log.debug("Marked {} devices offline", goingOffline.size());
  }

  private void bufferCommand(String deviceKey, Object command) {
    Queue<Object> queue = pendingCommands.get(deviceKey, k -> new ConcurrentLinkedQueue<>());
    if (queue.size() >= COMMAND_BUFFER_MAX) queue.poll();
    queue.add(command);
  }

  static Map<String, Object> sessionToDeviceMap(SessionEntity s) {
    var map = new LinkedHashMap<String, Object>();
    map.put("sessionId", s.getId().toString());
    map.put("deviceId", s.getDeviceId());
    map.put("deviceName", s.getDeviceName());
    map.put("clientName", s.getClientName());
    map.put("isOnline", s.isOnline());
    map.put("lastUpdate", s.getLastUpdate().toString());
    if (s.getNowPlayingItem() != null) {
      map.put("nowPlayingItemId", s.getNowPlayingItem().getId().toString());
      map.put("nowPlayingItemName", s.getNowPlayingItem().getName());
    }
    map.put("positionMs", s.getPositionMs());
    map.put("isPaused", s.getPaused());
    map.put("volumeLevel", s.getVolumeLevel());
    return map;
  }
}
