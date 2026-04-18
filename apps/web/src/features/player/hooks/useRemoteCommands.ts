import { useEffect } from 'react';
import { DeviceService, type RemoteCommand } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { usePlayerStore } from '../stores/player.store';
import { useGroupSyncStore } from '../stores/group-sync-store';

export function useRemoteCommands() {
  const client = useAuthStore(s => s.client);

  useEffect(() => {
    if (!client) return;

    const service = new DeviceService(client);
    let sseUrl: string;
    try {
      sseUrl = service.buildSseUrl('/v1/me/devices/commands');
    } catch {
      return;
    }

    let closed = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let reconnectAttempts = 0;

    const handleGroupCommand = (
      cmd: RemoteCommand,
      groupSync: ReturnType<typeof useGroupSyncStore.getState>
    ) => {
      const groupActions = ['PAUSE', 'PLAY', 'NEXT', 'PREV', 'SEEK'] as const;
      type GroupAction = (typeof groupActions)[number];
      if (!groupActions.includes(cmd.type as GroupAction)) return;

      const posMs =
        cmd.type === 'SEEK' && cmd.payload ? (cmd.payload.positionMs as number) : undefined;
      groupSync
        .sendAction(cmd.type as GroupAction, undefined, posMs, groupSync.schedule?.isPaused)
        .catch(() => {});
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
        default:
          log.player.debug('Unknown remote command', { type: cmd.type });
      }
    };

    const handleCommand = (event: MessageEvent) => {
      try {
        const cmd = JSON.parse(event.data as string) as RemoteCommand;
        const groupSync = useGroupSyncStore.getState();
        const store = usePlayerStore.getState();

        if (groupSync.mode === 'group' && cmd.type !== 'SET_VOLUME') {
          handleGroupCommand(cmd, groupSync);
          return;
        }

        handleSoloCommand(cmd, store);
      } catch {
        // ignore malformed
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
        const delay = Math.min(2000 * reconnectAttempts, 30000);
        reconnectTimer = setTimeout(connect, delay);
      };
      es.onopen = () => {
        reconnectAttempts = 0;
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
