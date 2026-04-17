import { create } from 'zustand';
import {
  ServerClock,
  GroupSyncService,
  DeviceService,
  ItemsService,
  type PlaybackSchedule,
  type GroupMember,
  type ScheduleAction,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { usePlayerStore } from './player.store';

const DRIFT_CHECK_MS = 500;
const DRIFT_DEAD_ZONE_MS = 30;
const DRIFT_HARD_SEEK_MS = 150;
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

function computeExpectedPosition(schedule: PlaybackSchedule, clock: ServerClock): number {
  if (schedule.isPaused) return schedule.anchorPositionMs;
  const elapsed = clock.serverNow() - schedule.anchorServerMs;
  return schedule.anchorPositionMs + Math.max(0, elapsed);
}

let lastHardSeekAt = 0;
const HARD_SEEK_COOLDOWN_MS = 2000;

function startDriftCorrection(store: typeof useGroupSyncStore) {
  if (driftIntervalId) return;

  driftIntervalId = setInterval(() => {
    const { schedule, mode } = store.getState();
    if (mode !== 'group' || !schedule || schedule.isPaused || !serverClock) return;

    const engine = (globalThis as Record<string, unknown>).__playerStore__ as
      | {
          readonly audioEngine: {
            getCurrentTime: () => number;
            seek: (s: number) => void;
            setPlaybackRate?: (rate: number) => void;
          } | null;
        }
      | undefined;
    if (!engine?.audioEngine) return;

    const expectedMs = computeExpectedPosition(schedule, serverClock);
    const actualMs = engine.audioEngine.getCurrentTime() * 1000;
    const drift = actualMs - expectedMs;

    store.setState({ driftMs: Math.round(drift) });

    if (Math.abs(drift) < DRIFT_DEAD_ZONE_MS) {
      if (engine.audioEngine.setPlaybackRate) {
        engine.audioEngine.setPlaybackRate(1.0);
      }
      return;
    }

    const now = performance.now();
    if (Math.abs(drift) >= DRIFT_HARD_SEEK_MS) {
      if (now - lastHardSeekAt < HARD_SEEK_COOLDOWN_MS) return;
      engine.audioEngine.seek(expectedMs / 1000);
      lastHardSeekAt = now;
      log.player.warn('Hard seek drift correction', { driftMs: Math.round(drift) });
      return;
    }

    // Soft correction: adjust playbackRate ±2%
    if (engine.audioEngine.setPlaybackRate) {
      const rate = Math.max(0.98, Math.min(1.02, 1 - drift / 5000));
      engine.audioEngine.setPlaybackRate(rate);
    }
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

  // Let browser handle reconnect with Last-Event-Id automatically
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
};

export const useGroupSyncStore = create<GroupSyncStore>()((set, get) => ({
  ...initialState,

  createGroup: async name => {
    const services = getServices();
    if (!services) return;

    const currentTrack = usePlayerStore.getState().currentTrack;
    if (!currentTrack) return;

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
      });

      get().applySchedule(snapshot.schedule);
      connectSSE(result.id, useGroupSyncStore);
      startDriftCorrection(useGroupSyncStore);
      startHeartbeat(useGroupSyncStore);

      log.player.info('Created group', { groupId: result.id, joinCode: result.joinCode });
    } catch (error) {
      log.player.error('Failed to create group', error);
    }
  },

  joinGroup: async joinCode => {
    const services = getServices();
    if (!services) return;

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
      });

      get().applySchedule(snapshot.schedule);

      connectSSE(snapshot.id, useGroupSyncStore);
      startDriftCorrection(useGroupSyncStore);
      startHeartbeat(useGroupSyncStore);

      log.player.info('Joined group', { groupId: snapshot.id });
    } catch (error) {
      log.player.error('Failed to join group', error);
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

    const expectedMs = computeExpectedPosition(schedule, serverClock);
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
              void player.playTrack(track).then(() => {
                if (!schedule.isPaused && serverClock) {
                  const nowExpected = computeExpectedPosition(schedule, serverClock);
                  player.seek(nowExpected / 1000);
                } else {
                  player.seek(schedule.anchorPositionMs / 1000);
                }
              });
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

  reset: () => {
    stopEngine();
    set(initialState);
  },
}));
