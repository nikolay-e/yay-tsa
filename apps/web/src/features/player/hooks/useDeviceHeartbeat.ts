import { useEffect } from 'react';
import { DeviceService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

const HEARTBEAT_INTERVAL_MS = 15_000;

export function useDeviceHeartbeat() {
  useEffect(() => {
    const send = () => {
      const client = useAuthStore.getState().client;
      if (!client) return;
      new DeviceService(client).heartbeat().catch(() => {});
    };

    send();
    const interval = setInterval(send, HEARTBEAT_INTERVAL_MS);
    return () => clearInterval(interval);
  }, []);
}
