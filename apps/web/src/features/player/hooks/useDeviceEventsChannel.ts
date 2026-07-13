import { useEffect } from 'react';
import {
  DeviceService,
  ItemsService,
  type AudioItem,
  type DeviceStateEvent,
  type RemoteCommand,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { getEffectiveDeviceId } from '../device-identity';
import { openReconnectingEventSource } from '../reconnecting-event-source';
import { usePlayerStore } from '../stores/player.store';
import { useGroupSyncStore } from '../stores/group-sync-store';
import { useDeviceStore } from '../stores/device-store';

function refetchDevicesSwallowingErrors(): void {
  useDeviceStore
    .getState()
    .fetchDevices()
    .catch(() => {});
}

function handleDeviceStateChanged(event: MessageEvent): void {
  try {
    const data = JSON.parse(event.data as string) as DeviceStateEvent;
    const store = useDeviceStore.getState();
    const knownDevice = store.devices.find(d => d.deviceId === data.deviceId);
    if (knownDevice) {
      store.updateDeviceState(knownDevice.deviceId, {
        positionMs: data.positionMs,
        isPaused: data.isPaused,
        nowPlayingItemId: data.nowPlayingItemId ?? undefined,
        nowPlayingItemName: data.nowPlayingItemName ?? undefined,
        isOnline: true,
      });
    } else {
      refetchDevicesSwallowingErrors();
    }
  } catch {
    log.player.debug('Discarded malformed device_state_changed event');
  }
}

function handleDeviceOffline(event: MessageEvent): void {
  try {
    const data = JSON.parse(event.data as string) as { deviceId: string };
    useDeviceStore.getState().setDeviceOffline(data.deviceId);
  } catch {
    log.player.debug('Discarded malformed device_offline event');
  }
}

function handleGroupCommand(
  cmd: RemoteCommand,
  groupSync: ReturnType<typeof useGroupSyncStore.getState>
): void {
  const groupActions = ['PAUSE', 'PLAY', 'NEXT', 'PREV', 'SEEK'] as const;
  type GroupAction = (typeof groupActions)[number];
  if (!groupActions.includes(cmd.type as GroupAction)) return;

  let posMs: number | undefined;
  if (cmd.type === 'SEEK') {
    // Never forward a non-number to sendAction — a NaN anchor would corrupt the
    // shared group schedule for every member. Drop the SEEK instead.
    if (!cmd.payload || typeof cmd.payload.positionMs !== 'number') return;
    posMs = cmd.payload.positionMs;
  }
  groupSync
    .sendAction(cmd.type as GroupAction, undefined, posMs, groupSync.schedule?.isPaused)
    .catch(() => {});
}

function createCommandHandler(itemsService: ItemsService): (event: MessageEvent) => void {
  const resolveQueueTracks = async (payload: RemoteCommand['payload']): Promise<AudioItem[]> => {
    const rawIds = payload?.trackIds;
    if (!Array.isArray(rawIds)) return [];
    const trackIds = rawIds.filter((id): id is string => typeof id === 'string' && id.length > 0);
    if (trackIds.length === 0) return [];
    const items = await itemsService.getItemsByIds(trackIds);
    const itemsById = new Map(items.map(item => [item.Id, item]));
    const missingIds = trackIds.filter(id => !itemsById.has(id));
    if (missingIds.length > 0) {
      log.player.warn('Remote queue command skipped unresolved tracks', {
        missing: missingIds,
      });
    }
    return trackIds
      .map(id => itemsById.get(id))
      .filter((item): item is AudioItem => item !== undefined);
  };

  const handleSoloCommand = (
    cmd: RemoteCommand,
    store: ReturnType<typeof usePlayerStore.getState>
  ) => {
    switch (cmd.type) {
      case 'PAUSE':
        store.pause();
        break;
      case 'PLAY':
        store.resume().catch(() => {});
        break;
      case 'STOP':
        // A device that lost its lease (transfer-away) must halt local audio at once
        // rather than wait for the next lazy reconciliation.
        store.pause();
        break;
      case 'NEXT':
        store.next().catch(() => {});
        break;
      case 'PREV':
        store.previous().catch(() => {});
        break;
      case 'SEEK':
        if (cmd.payload && typeof cmd.payload.positionMs === 'number') {
          store.seek(cmd.payload.positionMs / 1000);
        }
        break;
      case 'SET_VOLUME':
        if (cmd.payload && typeof cmd.payload.volume === 'number') {
          store.setVolume(cmd.payload.volume);
        }
        break;
      case 'TOGGLE_SHUFFLE':
        store.toggleShuffle();
        break;
      case 'TOGGLE_REPEAT':
        store.toggleRepeat();
        break;
      case 'CLEAR_QUEUE':
        // stop() empties the local queue and reports Stopped, so server-side
        // reflection converges to STOPPED instead of resurrecting the queue.
        store.stop();
        break;
      case 'ENQUEUE':
        resolveQueueTracks(cmd.payload)
          .then(tracks => {
            if (tracks.length > 0) usePlayerStore.getState().appendToQueue(tracks);
          })
          .catch((err: unknown) => {
            log.player.warn('Remote ENQUEUE failed', { error: String(err) });
          });
        break;
      case 'SET_QUEUE':
        resolveQueueTracks(cmd.payload)
          .then(async tracks => {
            if (tracks.length > 0) await usePlayerStore.getState().playTracks(tracks, 0);
          })
          .catch((err: unknown) => {
            log.player.warn('Remote SET_QUEUE failed', { error: String(err) });
          });
        break;
      default:
        log.player.debug('Unknown remote command', { type: cmd.type });
    }
  };

  const engineCommands = new Set([
    'PAUSE',
    'PLAY',
    'NEXT',
    'PREV',
    'SEEK',
    'STOP',
    'SET_VOLUME',
    'CLEAR_QUEUE',
    'ENQUEUE',
    'SET_QUEUE',
  ]);

  // Outbox delivery is at-least-once: a crash between the SSE emit and the publishedAt
  // commit re-delivers the same command on the next poll. NEXT/PREV are not idempotent,
  // so a redelivery would double-skip. Drop any command whose id we've already acted on.
  const SEEN_COMMAND_LIMIT = 64;
  const seenCommandIds = new Set<string>();
  const alreadySeen = (commandId: string): boolean => {
    if (seenCommandIds.has(commandId)) return true;
    seenCommandIds.add(commandId);
    if (seenCommandIds.size > SEEN_COMMAND_LIMIT) {
      const oldest = seenCommandIds.values().next().value;
      if (oldest !== undefined) seenCommandIds.delete(oldest);
    }
    return false;
  };

  return (event: MessageEvent) => {
    try {
      const cmd = JSON.parse(event.data as string) as RemoteCommand;
      // Only dedup when the backend stamped an id; older backends omit it and act every time.
      if (cmd.commandId && alreadySeen(cmd.commandId)) return;
      if (engineCommands.has(cmd.type)) {
        // Lease/remote-control commands are always device-targeted by the backend.
        // Fail closed: act only on an exact match, never on an untargeted broadcast.
        if (cmd.targetDeviceId !== getEffectiveDeviceId()) return;
      } else if (cmd.targetDeviceId && cmd.targetDeviceId !== getEffectiveDeviceId()) {
        return;
      }

      const groupSync = useGroupSyncStore.getState();
      const store = usePlayerStore.getState();

      if (cmd.type !== 'STOP' && groupSync.mode === 'group' && cmd.type !== 'SET_VOLUME') {
        handleGroupCommand(cmd, groupSync);
        return;
      }

      handleSoloCommand(cmd, store);
    } catch {
      log.player.debug('Discarded malformed remote command event');
    }
  };
}

// Device-state events and remote commands ride one SSE channel; a single EventSource
// carries both handler sets so the app holds one connection, not two.
export function useDeviceEventsChannel() {
  const client = useAuthStore(s => s.client);

  useEffect(() => {
    if (!client) return;

    refetchDevicesSwallowingErrors();

    const service = new DeviceService(client);
    let sseUrl: string;
    try {
      sseUrl = service.buildSseUrl('/v1/me/devices/events');
    } catch {
      return;
    }

    return openReconnectingEventSource({
      url: sseUrl,
      stream: 'devices/events',
      listeners: {
        device_state_changed: handleDeviceStateChanged,
        device_offline: handleDeviceOffline,
        command: createCommandHandler(new ItemsService(client)),
      },
      onReconnect: refetchDevicesSwallowingErrors,
    });
  }, [client]);
}
