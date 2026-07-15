import { create } from 'zustand';
import {
  ServerClock,
  GroupSyncService,
  DeviceService,
  ItemsService,
  DRIFT_DEAD_ZONE_MS,
  DRIFT_HARD_SEEK_MS,
  HARD_SEEK_COOLDOWN_MS,
  computeExpectedPositionMs,
  driftCorrectionRate,
  type PlaybackSchedule,
  type GroupMember,
  type GroupControlMode,
  type GroupSnapshot,
  type ScheduleAction,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { toError } from '@/shared/utils/to-error';
import { usePlayerStore, getPlayerEngineForSync } from './player.store';

const DRIFT_CHECK_MS = 500;
const HEARTBEAT_INTERVAL_MS = 10_000;

interface GroupSyncState {
  groupId: string | null;
  joinCode: string | null;
  isOwner: boolean;
  members: GroupMember[];
  schedule: PlaybackSchedule | null;
  currentEpoch: number;
  mode: 'solo' | 'group';
  driftMs: number;
  controlMode: GroupControlMode;
}

interface GroupSyncActions {
  createGroup: (name: string) => Promise<void>;
  joinGroup: (joinCode: string) => Promise<void>;
  leaveGroup: () => Promise<void>;
  sendAction: (
    action: ScheduleAction,
    trackId?: string,
    positionMs?: number,
    paused?: boolean
  ) => Promise<void>;
  applySchedule: (schedule: PlaybackSchedule) => void;
  reconcileSnapshot: () => Promise<void>;
  setControlMode: (controlMode: GroupControlMode) => Promise<void>;
  reset: () => void;
}

type GroupSyncStore = GroupSyncState & GroupSyncActions;

let serverClock: ServerClock | null = null;
let driftIntervalId: ReturnType<typeof setInterval> | null = null;
let heartbeatIntervalId: ReturnType<typeof setInterval> | null = null;
let sseSource: EventSource | null = null;

function getServices() {
  const client = useAuthStore.getState().client;
  if (!client) return null;
  return {
    sync: new GroupSyncService(client),
    device: new DeviceService(client),
    items: new ItemsService(client),
  };
}

function stopEngine() {
  if (driftIntervalId) {
    clearInterval(driftIntervalId);
    driftIntervalId = null;
  }
  if (heartbeatIntervalId) {
    clearInterval(heartbeatIntervalId);
    heartbeatIntervalId = null;
  }
  if (sseSource) {
    sseSource.close();
    sseSource = null;
  }
  serverClock?.stop();
  serverClock = null;
}

async function ensureClock(): Promise<ServerClock> {
  if (serverClock?.isReady()) return serverClock;
  const client = useAuthStore.getState().client;
  if (!client) throw new Error('Not authenticated');
  const serverUrl = client.getServerUrl();
  serverClock = new ServerClock(`${serverUrl}/v1/time`);
  await serverClock.start();
  return serverClock;
}

let lastHardSeekAt = 0;

function startDriftCorrection(store: typeof useGroupSyncStore) {
  if (driftIntervalId) return;

  driftIntervalId = setInterval(() => {
    const { schedule, mode } = store.getState();
    if (mode !== 'group' || !schedule || schedule.isPaused || !serverClock) return;

    const engine = getPlayerEngineForSync();
    if (!engine) return;

    const expectedMs = computeExpectedPositionMs(schedule, serverClock.serverNow());
    const actualMs = engine.getCurrentTime() * 1000;
    const drift = actualMs - expectedMs;

    store.setState({ driftMs: Math.round(drift) });

    if (Math.abs(drift) < DRIFT_DEAD_ZONE_MS) {
      engine.setPlaybackRate?.(1);
      return;
    }

    const now = performance.now();
    if (Math.abs(drift) >= DRIFT_HARD_SEEK_MS) {
      if (now - lastHardSeekAt < HARD_SEEK_COOLDOWN_MS) return;
      engine.seek(expectedMs / 1000);
      lastHardSeekAt = now;
      log.player.warn('Hard seek drift correction', { driftMs: Math.round(drift) });
      return;
    }

    // Soft correction: adjust playbackRate ±2%
    engine.setPlaybackRate?.(driftCorrectionRate(drift));
  }, DRIFT_CHECK_MS);
}

function startHeartbeat(store: typeof useGroupSyncStore) {
  if (heartbeatIntervalId) return;
  heartbeatIntervalId = setInterval(() => {
    const { groupId, mode } = store.getState();
    if (mode !== 'group' || !groupId) return;
    const services = getServices();
    if (!services) return;
    const rttMs = serverClock ? Math.round(serverClock.getRtt()) : undefined;
    services.sync.heartbeat(groupId, rttMs).catch(() => {});
  }, HEARTBEAT_INTERVAL_MS);
}

function connectSSE(groupId: string, store: typeof useGroupSyncStore) {
  const client = useAuthStore.getState().client;
  if (!client) return;
  const service = new DeviceService(client);
  const url = service.buildSseUrl(`/v1/groups/${groupId}/events`);

  sseSource = new EventSource(url);

  sseSource.addEventListener('schedule_changed', (event: MessageEvent) => {
    try {
      const schedule = JSON.parse(event.data as string) as PlaybackSchedule;
      const { currentEpoch } = store.getState();
      if (schedule.scheduleEpoch <= currentEpoch) return;
      store.getState().applySchedule(schedule);
    } catch {
      // ignore
    }
  });

  sseSource.addEventListener('group_ended', () => {
    store.getState().reset();
  });

  sseSource.addEventListener('member_joined', (event: MessageEvent) => {
    try {
      const raw = JSON.parse(event.data as string) as { deviceId: string; userId: string };
      const data: GroupMember = {
        deviceId: raw.deviceId,
        userId: raw.userId,
        stale: false,
        reportedLatencyMs: 0,
      };
      store.setState(state => ({
        members: [...state.members.filter(m => m.deviceId !== data.deviceId), data],
      }));
    } catch {
      // ignore
    }
  });

  sseSource.addEventListener('member_left', (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data as string) as { deviceId: string };
      store.setState(state => ({
        members: state.members.filter(m => m.deviceId !== data.deviceId),
      }));
    } catch {
      // ignore
    }
  });

  // The browser auto-reconnects with Last-Event-Id, but the in-memory broadcaster keeps
  // no replay buffer: a schedule_changed emitted during the gap is lost and the client
  // (which only advances on a strictly-increasing epoch) would desync permanently. On
  // every successful (re)open after the first, refetch the snapshot to close that hole.
  let sawOpen = false;
  sseSource.onopen = () => {
    if (sawOpen) {
      store
        .getState()
        .reconcileSnapshot()
        .catch(() => {});
    }
    sawOpen = true;
  };

  // Only close manually on leaveGroup/reset via stopEngine()
  sseSource.onerror = () => {
    log.player.debug('Group SSE error, browser will auto-reconnect');
  };
}

const initialState: GroupSyncState = {
  groupId: null,
  joinCode: null,
  isOwner: false,
  members: [],
  schedule: null,
  currentEpoch: 0,
  mode: 'solo',
  driftMs: 0,
  controlMode: 'host',
};

export const useGroupSyncStore = create<GroupSyncStore>()((set, get) => ({
  ...initialState,

  createGroup: async name => {
    const services = getServices();
    if (!services) throw new Error('Not authenticated');

    const currentTrack = usePlayerStore.getState().currentTrack;
    if (!currentTrack) throw new Error('Nothing is playing');

    try {
      await ensureClock();
      const result = await services.sync.createGroup(name, currentTrack.Id);
      const snapshot = await services.sync.getSnapshot(result.id);

      set({
        groupId: result.id,
        joinCode: result.joinCode,
        isOwner: true,
        members: snapshot.members,
        schedule: snapshot.schedule,
        currentEpoch: snapshot.schedule.scheduleEpoch,
        mode: 'group',
        controlMode: snapshot.controlMode ?? 'host',
      });

      get().applySchedule(snapshot.schedule);
      connectSSE(result.id, useGroupSyncStore);
      startDriftCorrection(useGroupSyncStore);
      startHeartbeat(useGroupSyncStore);

      log.player.info('Created group', { groupId: result.id, joinCode: result.joinCode });
    } catch (error) {
      log.player.error('Failed to create group', error);
      throw toError(error);
    }
  },

  joinGroup: async joinCode => {
    const services = getServices();
    if (!services) throw new Error('Not authenticated');

    try {
      await ensureClock();
      const snapshot = await services.sync.joinGroup(joinCode);
      const userId = useAuthStore.getState().userId;

      set({
        groupId: snapshot.id,
        joinCode: snapshot.joinCode,
        isOwner: snapshot.ownerId === userId,
        members: snapshot.members,
        schedule: snapshot.schedule,
        currentEpoch: snapshot.schedule.scheduleEpoch,
        mode: 'group',
        controlMode: snapshot.controlMode ?? 'host',
      });

      get().applySchedule(snapshot.schedule);

      connectSSE(snapshot.id, useGroupSyncStore);
      startDriftCorrection(useGroupSyncStore);
      startHeartbeat(useGroupSyncStore);

      log.player.info('Joined group', { groupId: snapshot.id });
    } catch (error) {
      log.player.error('Failed to join group', error);
      throw toError(error);
    }
  },

  leaveGroup: async () => {
    const { groupId } = get();
    const services = getServices();
    if (!services || !groupId) return;

    const deviceId = useAuthStore.getState().client?.getClientInfo().deviceId;
    if (deviceId) {
      await services.sync.leaveGroup(groupId, deviceId).catch(() => {});
    }

    get().reset();
  },

  sendAction: async (action, trackId, positionMs, paused) => {
    const { groupId, currentEpoch } = get();
    const services = getServices();
    if (!services || !groupId) return;

    try {
      const result = await services.sync.updateSchedule(
        groupId,
        currentEpoch,
        action,
        trackId,
        positionMs,
        paused
      );
      get().applySchedule(result.schedule);
    } catch (error) {
      if (String(error).includes('409')) {
        log.player.info('Schedule conflict, refetching');
        const snapshot = await services.sync.getSnapshot(groupId);
        get().applySchedule(snapshot.schedule);
      } else {
        log.player.error('Schedule update failed', error);
      }
    }
  },

  applySchedule: schedule => {
    if (!serverClock) return;

    set({ schedule, currentEpoch: schedule.scheduleEpoch });

    const expectedMs = computeExpectedPositionMs(schedule, serverClock.serverNow());
    const player = usePlayerStore.getState();
    const currentTrackId = player.currentTrack?.Id;

    if (currentTrackId !== schedule.trackId) {
      // Track changed — need to load new track
      const services = getServices();
      if (services) {
        services.items
          .getItemsByIds([schedule.trackId])
          .then(items => {
            const track = items[0];
            if (track) {
              player
                .playTrack(track)
                .then(() => {
                  if (!schedule.isPaused && serverClock) {
                    const nowExpected = computeExpectedPositionMs(
                      schedule,
                      serverClock.serverNow()
                    );
                    player.seek(nowExpected / 1000);
                  } else {
                    player.seek(schedule.anchorPositionMs / 1000);
                    player.pause();
                  }
                })
                .catch(() => {});
            }
          })
          .catch(() => {});
      }
      return;
    }

    if (schedule.isPaused) {
      player.pause();
      player.seek(schedule.anchorPositionMs / 1000);
    } else {
      player.seek(expectedMs / 1000);
      player.resume().catch(() => {});
    }
  },

  reconcileSnapshot: async () => {
    const { groupId } = get();
    const services = getServices();
    if (!services || !groupId) return;
    let snapshot: GroupSnapshot;
    try {
      snapshot = await services.sync.getSnapshot(groupId);
    } catch {
      return;
    }
    if (get().groupId !== groupId) return;

    const userId = useAuthStore.getState().userId;
    set({
      members: snapshot.members,
      isOwner: snapshot.ownerId === userId,
      controlMode: snapshot.controlMode ?? get().controlMode,
    });

    // The reconnect snapshot is authoritative: adopt its schedule regardless of epoch
    // ordering. The strict-> monotonicity guard belongs only on the live SSE event,
    // where out-of-order/duplicate delivery is the concern. After an optimistic local
    // action bumped currentEpoch, an equal/lower authoritative epoch would otherwise be
    // skipped, leaving this device desynced (e.g. still playing while the group paused).
    get().applySchedule(snapshot.schedule);
  },

  setControlMode: async controlMode => {
    const { groupId, controlMode: previous } = get();
    const services = getServices();
    if (!services || !groupId) return;
    set({ controlMode });
    try {
      await services.sync.setControlMode(groupId, controlMode);
    } catch (error) {
      log.player.warn('Failed to set group control mode', { error: String(error) });
      set({ controlMode: previous });
    }
  },

  reset: () => {
    stopEngine();
    set(initialState);
  },
}));
