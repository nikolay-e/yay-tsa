package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackGroupEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackGroupMemberEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackScheduleEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackGroupMemberRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackGroupRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackScheduleRepository;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class PlaybackGroupService {

  private static final long STALE_TIMEOUT_MINUTES = 3;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PlaybackGroupRepository groupRepository;
  private final PlaybackGroupMemberRepository memberRepository;
  private final PlaybackScheduleRepository scheduleRepository;
  private final ListeningSessionService sessionService;

  public PlaybackGroupService(
      PlaybackGroupRepository groupRepository,
      PlaybackGroupMemberRepository memberRepository,
      PlaybackScheduleRepository scheduleRepository,
      ListeningSessionService sessionService) {
    this.groupRepository = groupRepository;
    this.memberRepository = memberRepository;
    this.scheduleRepository = scheduleRepository;
    this.sessionService = sessionService;
  }

  @Transactional
  public PlaybackGroupEntity createGroup(
      UUID userId, String deviceId, String name, UUID firstTrackId) {
    ListeningSessionEntity session = sessionService.findActiveSession(userId);
    if (session == null) {
      throw new IllegalStateException("No active listening session");
    }

    groupRepository.findActiveByOwnerId(userId).ifPresent(this::endGroupInternal);

    var group = new PlaybackGroupEntity();
    group.setOwner(session.getUser());
    group.setListeningSession(session);
    group.setCanonicalDeviceId(deviceId);
    group.setName(name);
    group.setJoinCode(generateUniqueJoinCode());
    group.setCreatedAt(OffsetDateTime.now());
    group = groupRepository.save(group);

    addMember(group.getId(), deviceId, userId);

    var schedule = new PlaybackScheduleEntity();
    schedule.setGroupId(group.getId());
    schedule.setTrackId(firstTrackId);
    schedule.setAnchorServerMs(System.currentTimeMillis());
    schedule.setAnchorPositionMs(0);
    schedule.setPaused(true);
    schedule.setScheduleEpoch(1);
    schedule.setUpdatedAt(OffsetDateTime.now());
    scheduleRepository.save(schedule);

    log.info(
        "Created group {} with join code {} for user {}",
        group.getId(),
        group.getJoinCode(),
        userId);
    return group;
  }

  @Transactional
  public PlaybackGroupEntity joinGroup(String joinCode, String deviceId, UUID userId) {
    var group =
        groupRepository
            .findActiveByJoinCode(joinCode)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.Item, "Invalid join code"));

    if (group.getListeningSession().getEndedAt() != null) {
      endGroupInternal(group);
      throw new IllegalStateException("Group session has ended");
    }

    var existing = memberRepository.findByGroupIdAndDeviceId(group.getId(), deviceId);
    if (existing.isPresent()) {
      var member = existing.get();
      member.setStale(false);
      member.setLastHeartbeatAt(OffsetDateTime.now());
      memberRepository.save(member);
    } else {
      addMember(group.getId(), deviceId, userId);
    }

    log.info("Device {} joined group {}", deviceId, group.getId());
    return group;
  }

  @Transactional(readOnly = true)
  public GroupSnapshot getSnapshot(UUID groupId) {
    var group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, groupId));
    var schedule =
        scheduleRepository
            .findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, groupId));
    var members = memberRepository.findByGroupId(groupId);
    return new GroupSnapshot(group, schedule, members);
  }

  @Transactional
  public LeaveResult leaveGroup(UUID groupId, String deviceId, UUID requestingUserId) {
    var group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, groupId));

    memberRepository.deleteByGroupIdAndDeviceId(groupId, deviceId);
    memberRepository.flush();

    if (deviceId.equals(group.getCanonicalDeviceId())) {
      var remaining =
          memberRepository.findActiveMembers(groupId).stream()
              .filter(m -> m.getUserId().equals(group.getOwner().getId()))
              .findFirst();

      if (remaining.isPresent()) {
        group.setCanonicalDeviceId(remaining.get().getDeviceId());
        groupRepository.save(group);
        log.info(
            "Canonical device reassigned to {} in group {}",
            remaining.get().getDeviceId(),
            groupId);
      } else {
        endGroupInternal(group);
        return LeaveResult.GROUP_ENDED;
      }
    }

    if (memberRepository.countByGroupIdAndStaleFalse(groupId) == 0) {
      endGroupInternal(group);
      return LeaveResult.GROUP_ENDED;
    }

    log.info("Device {} left group {}", deviceId, groupId);
    return LeaveResult.LEFT;
  }

  public enum LeaveResult {
    LEFT,
    GROUP_ENDED
  }

  @Transactional
  public void endGroup(UUID groupId, UUID requestingUserId) {
    var group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, groupId));

    if (!group.getOwner().getId().equals(requestingUserId)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Only owner can end group");
    }

    endGroupInternal(group);
  }

  @Transactional
  public HeartbeatResult heartbeat(UUID groupId, String deviceId, Integer rttMs) {
    var member = memberRepository.findByGroupIdAndDeviceId(groupId, deviceId).orElse(null);
    if (member == null) {
      return HeartbeatResult.NOT_FOUND;
    }

    boolean wasStale = member.isStale();
    member.setLastHeartbeatAt(OffsetDateTime.now());
    member.setStale(false);
    if (rttMs != null) member.setReportedRttMs(rttMs);
    memberRepository.save(member);

    if (wasStale) {
      log.info("Stale device {} rejoined group {}", deviceId, groupId);
      return HeartbeatResult.REJOINED;
    }
    return HeartbeatResult.OK;
  }

  public void verifyMembership(UUID groupId, String deviceId) {
    var member = memberRepository.findByGroupIdAndDeviceId(groupId, deviceId).orElse(null);
    if (member == null) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Not a member of this group");
    }
    if (member.isStale()) {
      member.setStale(false);
      member.setLastHeartbeatAt(OffsetDateTime.now());
      memberRepository.save(member);
    }
  }

  public enum HeartbeatResult {
    OK,
    REJOINED,
    NOT_FOUND
  }

  @Transactional
  public ScheduleUpdateResult updateSchedule(
      UUID groupId,
      String deviceId,
      long expectedEpoch,
      UUID trackId,
      int resumeBufferMs,
      long anchorPositionMs,
      boolean isPaused,
      UUID nextTrackId,
      Long nextTrackAnchorMs) {

    verifyMembership(groupId, deviceId);

    int updated =
        scheduleRepository.updateSchedule(
            groupId,
            expectedEpoch,
            trackId,
            resumeBufferMs,
            anchorPositionMs,
            isPaused,
            nextTrackId,
            nextTrackAnchorMs);

    if (updated == 0) {
      return new ScheduleUpdateResult(false, null, null);
    }

    var schedule = scheduleRepository.findById(groupId).orElse(null);
    var group = groupRepository.findById(groupId).orElse(null);
    UUID sessionId = group != null ? group.getListeningSession().getId() : null;

    return new ScheduleUpdateResult(true, schedule, sessionId);
  }

  @Transactional(readOnly = true)
  public UUID getSessionIdForGroup(UUID groupId) {
    return groupRepository.findById(groupId).map(g -> g.getListeningSession().getId()).orElse(null);
  }

  @Transactional(readOnly = true)
  public long computeCurrentPositionMs(UUID groupId) {
    var schedule = scheduleRepository.findById(groupId).orElse(null);
    if (schedule == null) return 0;
    if (schedule.isPaused()) return schedule.getAnchorPositionMs();
    long elapsed = System.currentTimeMillis() - schedule.getAnchorServerMs();
    return schedule.getAnchorPositionMs() + Math.max(0, elapsed);
  }

  public int getAdaptiveResumeBufferMs(UUID groupId) {
    var members = memberRepository.findActiveMembers(groupId);
    if (members.isEmpty()) return 300;

    int maxRtt =
        members.stream()
            .map(PlaybackGroupMemberEntity::getReportedRttMs)
            .filter(rtt -> rtt != null)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(50);

    return Math.min(2000, Math.max(300, maxRtt * 2 + 200));
  }

  @Scheduled(fixedRate = 60000)
  @Transactional
  public void markStaleMembers() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_TIMEOUT_MINUTES);
    int marked = memberRepository.markAllStaleMembers(cutoff);
    if (marked > 0) {
      log.debug("Marked {} members stale across all active groups", marked);
    }
  }

  private void addMember(UUID groupId, String deviceId, UUID userId) {
    var member = new PlaybackGroupMemberEntity();
    member.setGroupId(groupId);
    member.setDeviceId(deviceId);
    member.setUserId(userId);
    member.setJoinedAt(OffsetDateTime.now());
    member.setLastHeartbeatAt(OffsetDateTime.now());
    member.setStale(false);
    member.setReportedLatencyMs(0);
    memberRepository.save(member);
  }

  private void endGroupInternal(PlaybackGroupEntity group) {
    group.setEndedAt(OffsetDateTime.now());
    groupRepository.save(group);
    log.info("Group {} ended", group.getId());
  }

  private String generateUniqueJoinCode() {
    for (int attempt = 0; attempt < 5; attempt++) {
      String code = String.valueOf(RANDOM.nextInt(900_000) + 100_000);
      if (groupRepository.findActiveByJoinCode(code).isEmpty()) {
        return code;
      }
    }
    return String.valueOf(RANDOM.nextInt(900_000) + 100_000);
  }

  public static java.util.Map<String, Object> scheduleToMap(PlaybackScheduleEntity s) {
    var map = new java.util.LinkedHashMap<String, Object>();
    map.put("groupId", s.getGroupId().toString());
    map.put("trackId", s.getTrackId().toString());
    map.put("anchorServerMs", s.getAnchorServerMs());
    map.put("anchorPositionMs", s.getAnchorPositionMs());
    map.put("isPaused", s.isPaused());
    map.put("scheduleEpoch", s.getScheduleEpoch());
    if (s.getNextTrackId() != null) map.put("nextTrackId", s.getNextTrackId().toString());
    if (s.getNextTrackAnchorMs() != null) map.put("nextTrackAnchorMs", s.getNextTrackAnchorMs());
    return map;
  }

  public record GroupSnapshot(
      PlaybackGroupEntity group,
      PlaybackScheduleEntity schedule,
      List<PlaybackGroupMemberEntity> members) {}

  public record ScheduleUpdateResult(
      boolean success, PlaybackScheduleEntity schedule, UUID sessionId) {}

  public record MemberEvent(String groupId, String deviceId, String userId) {}
}
