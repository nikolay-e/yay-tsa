import { useEffect } from 'react';
import { DeviceService, type DeviceStateEvent } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useDeviceStore } from '../stores/device-store';

export function useDeviceEvents() {
  useEffect(() => {
    const client = useAuthStore.getState().client;
    if (!client) return;

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

    const connect = () => {
      if (closed) return;
      es = new EventSource(sseUrl);

      es.addEventListener('device_state_changed', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data as string) as DeviceStateEvent;
          useDeviceStore.getState().updateDeviceState(data.deviceId, {
            positionMs: data.positionMs,
            isPaused: data.isPaused,
            nowPlayingItemId: data.nowPlayingItemId,
            isOnline: true,
          });
        } catch {
          // ignore
        }
      });

      es.addEventListener('device_offline', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data as string) as { deviceId: string };
          useDeviceStore.getState().setDeviceOffline(data.deviceId);
        } catch {
          // ignore
        }
      });

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
  }, []);
}
