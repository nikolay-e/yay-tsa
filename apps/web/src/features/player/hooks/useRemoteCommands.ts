import { useEffect } from 'react';
import {
  DeviceService,
  ItemsService,
  getOrCreateDeviceId,
  type AudioItem,
  type RemoteCommand,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { usePlayerStore } from '../stores/player.store';
import { useGroupSyncStore } from '../stores/group-sync-store';

const DEGRADED_STREAM_THRESHOLD = 5;

export function useRemoteCommands() {
  const client = useAuthStore(s => s.client);

  useEffect(() => {
    if (!client) return;

    const service = new DeviceService(client);
    const itemsService = new ItemsService(client);
    const ownDeviceId = getOrCreateDeviceId();
    let sseUrl: string;
    try {
      // Remote commands ride the same SSE channel as device-state events; the backend
      // tags each command with the target device id and we act only on our own.
      sseUrl = service.buildSseUrl('/v1/me/devices/events');
    } catch {
      return;
    }

    let closed = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let reconnectAttempts = 0;
    let consecutiveFailures = 0;
    let degradedReported = false;

    const handleGroupCommand = (
      cmd: RemoteCommand,
      groupSync: ReturnType<typeof useGroupSyncStore.getState>
    ) => {
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
    };

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

    const handleCommand = (event: MessageEvent) => {
      try {
        const cmd = JSON.parse(event.data as string) as RemoteCommand;
        // Only dedup when the backend stamped an id; older backends omit it and act every time.
        if (cmd.commandId && alreadySeen(cmd.commandId)) return;
        if (engineCommands.has(cmd.type)) {
          // Lease/remote-control commands are always device-targeted by the backend.
          // Fail closed: act only on an exact match, never on an untargeted broadcast.
          if (cmd.targetDeviceId !== ownDeviceId) return;
        } else if (cmd.targetDeviceId && cmd.targetDeviceId !== ownDeviceId) {
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

    const connect = () => {
      if (closed) return;
      es = new EventSource(sseUrl);
      es.addEventListener('command', handleCommand);
      es.onerror = () => {
        es?.close();
        es = null;
        if (closed) return;
        reconnectAttempts++;
        consecutiveFailures++;
        if (consecutiveFailures >= DEGRADED_STREAM_THRESHOLD && !degradedReported) {
          degradedReported = true;
          log.player.warn('Device event stream degraded', {
            stream: 'devices/events',
            attempts: consecutiveFailures,
          });
        }
        const delay = Math.min(2000 * reconnectAttempts, 30000);
        reconnectTimer = setTimeout(connect, delay);
      };
      es.onopen = () => {
        reconnectAttempts = 0;
        consecutiveFailures = 0;
        degradedReported = false;
      };
    };

    connect();

    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      es?.close();
    };
  }, [client]);
}
