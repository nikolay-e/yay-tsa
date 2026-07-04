import { useEffect } from 'react';
import { DeviceService, type DeviceStateEvent } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { useDeviceStore } from '../stores/device-store';

const DEGRADED_STREAM_THRESHOLD = 5;

function refetchDevicesSwallowingErrors(): void {
  useDeviceStore
    .getState()
    .fetchDevices()
    .catch(() => {});
}

export function useDeviceEvents() {
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

    let closed = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let reconnectAttempts = 0;
    let consecutiveFailures = 0;
    let degradedReported = false;
    let sawOpen = false;

    const handleDeviceStateChanged = (event: MessageEvent) => {
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
    };

    const handleDeviceOffline = (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data as string) as { deviceId: string };
        useDeviceStore.getState().setDeviceOffline(data.deviceId);
      } catch {
        log.player.debug('Discarded malformed device_offline event');
      }
    };

    const connect = () => {
      if (closed) return;
      es = new EventSource(sseUrl);

      es.addEventListener('device_state_changed', handleDeviceStateChanged);
      es.addEventListener('device_offline', handleDeviceOffline);

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
        // A device_state_changed lost while the stream was down would leave a stale list;
        // refetch the authoritative snapshot on every reconnect (not the first open).
        if (sawOpen) {
          refetchDevicesSwallowingErrors();
        }
        sawOpen = true;
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
