package com.yaytsa.server.domain.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class QueueSseService {

  private static final Logger log = LoggerFactory.getLogger(QueueSseService.class);
  private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

  private final Map<UUID, List<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();

  public SseEmitter createEmitter(UUID sessionId) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

    sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
    emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
    emitter.onError(e -> removeEmitter(sessionId, emitter));

    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException e) {
      log.debug("Failed to send initial SSE event for session {}", sessionId);
      removeEmitter(sessionId, emitter);
    }

    return emitter;
  }

  public void broadcast(UUID sessionId, String eventName, Object data) {
    List<SseEmitter> emitters = sessionEmitters.get(sessionId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(data));
      } catch (IOException e) {
        deadEmitters.add(emitter);
      }
    }
    emitters.removeAll(deadEmitters);
  }

  public void cleanupSession(UUID sessionId) {
    List<SseEmitter> emitters = sessionEmitters.remove(sessionId);
    if (emitters != null) {
      emitters.forEach(SseEmitter::complete);
    }
  }

  @Scheduled(fixedRate = 15000)
  public void sendHeartbeats() {
    sessionEmitters.forEach(
        (sessionId, emitters) -> {
          List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
          for (SseEmitter emitter : emitters) {
            try {
              emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
              deadEmitters.add(emitter);
            }
          }
          emitters.removeAll(deadEmitters);
          if (emitters.isEmpty()) {
            sessionEmitters.remove(sessionId);
          }
        });
  }

  private void removeEmitter(UUID sessionId, SseEmitter emitter) {
    List<SseEmitter> emitters = sessionEmitters.get(sessionId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        sessionEmitters.remove(sessionId);
      }
    }
  }
}
