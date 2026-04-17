package com.yaytsa.server.domain.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Slf4j
public class DeviceSseService {

  private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

  private final Map<UUID, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
  private final Map<String, List<SseEmitter>> deviceCommandEmitters = new ConcurrentHashMap<>();

  public SseEmitter createDeviceListEmitter(UUID userId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> removeUserEmitter(userId, emitter));
    emitter.onTimeout(() -> removeUserEmitter(userId, emitter));
    emitter.onError(e -> removeUserEmitter(userId, emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException e) {
      removeUserEmitter(userId, emitter);
    }
    return emitter;
  }

  public SseEmitter createCommandEmitter(String deviceKey) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    deviceCommandEmitters
        .computeIfAbsent(deviceKey, k -> new CopyOnWriteArrayList<>())
        .add(emitter);
    emitter.onCompletion(() -> removeCommandEmitter(deviceKey, emitter));
    emitter.onTimeout(() -> removeCommandEmitter(deviceKey, emitter));
    emitter.onError(e -> removeCommandEmitter(deviceKey, emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException e) {
      removeCommandEmitter(deviceKey, emitter);
    }
    return emitter;
  }

  public void broadcastToUser(UUID userId, String eventName, Object data) {
    List<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters == null || emitters.isEmpty()) return;
    List<SseEmitter> dead = new ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(data));
      } catch (IOException e) {
        dead.add(emitter);
      }
    }
    emitters.removeAll(dead);
  }

  public boolean sendCommand(String deviceKey, Object command) {
    List<SseEmitter> emitters = deviceCommandEmitters.get(deviceKey);
    if (emitters == null || emitters.isEmpty()) return false;
    boolean delivered = false;
    List<SseEmitter> dead = new ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("command").data(command));
        delivered = true;
      } catch (IOException e) {
        dead.add(emitter);
      }
    }
    emitters.removeAll(dead);
    return delivered;
  }

  @Scheduled(fixedRate = 15000)
  public void sendHeartbeats() {
    sendHeartbeatsFor(userEmitters);
    sendHeartbeatsFor(deviceCommandEmitters);
  }

  private <K> void sendHeartbeatsFor(Map<K, List<SseEmitter>> registry) {
    registry.forEach(
        (key, emitters) -> {
          List<SseEmitter> dead = new ArrayList<>();
          for (SseEmitter emitter : emitters) {
            try {
              emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
              dead.add(emitter);
            }
          }
          emitters.removeAll(dead);
          if (emitters.isEmpty()) registry.remove(key);
        });
  }

  public static String deviceKey(UUID userId, String deviceId) {
    return userId + ":" + deviceId;
  }

  private void removeUserEmitter(UUID userId, SseEmitter emitter) {
    List<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) userEmitters.remove(userId);
    }
  }

  private void removeCommandEmitter(String deviceKey, SseEmitter emitter) {
    List<SseEmitter> emitters = deviceCommandEmitters.get(deviceKey);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) deviceCommandEmitters.remove(deviceKey);
    }
  }
}
