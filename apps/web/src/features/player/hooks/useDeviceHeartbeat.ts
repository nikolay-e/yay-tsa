import { useEffect } from 'react';
import { DeviceService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { adoptServerDeviceId } from '../device-identity';

const HEARTBEAT_INTERVAL_MS = 15_000;
// Single transient failures are expected (deploy windows, brief network blips) and stay silent.
// Only a sustained run surfaces — a real "this device went offline" signal worth telemetry.
const HEARTBEAT_FAILURE_ALERT_THRESHOLD = 3;

export function useDeviceHeartbeat() {
  const client = useAuthStore(s => s.client);

  useEffect(() => {
    if (!client) return;

    let consecutiveFailures = 0;
    const send = () => {
      new DeviceService(client)
        .heartbeat()
        .then(ack => {
          consecutiveFailures = 0;
          if (ack?.deviceId) adoptServerDeviceId(ack.deviceId);
        })
        .catch(() => {
          consecutiveFailures += 1;
          if (consecutiveFailures === HEARTBEAT_FAILURE_ALERT_THRESHOLD) {
            log.player.warn('Device heartbeat failing repeatedly', { consecutiveFailures });
          }
        });
    };

    send();
    const interval = setInterval(send, HEARTBEAT_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [client]);
}
