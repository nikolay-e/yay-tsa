import { getOrCreateDeviceId } from '@yay-tsa/core';

// Remote commands and lease targeting use the deviceId the SERVER knows — the token-bound
// identity echoed by the heartbeat — which can drift from the locally generated id on
// legacy persisted sessions. Until the first heartbeat ack lands, fall back to the local id.
let serverConfirmedDeviceId: string | null = null;

export function adoptServerDeviceId(deviceId: string): void {
  serverConfirmedDeviceId = deviceId;
}

export function getEffectiveDeviceId(): string {
  return serverConfirmedDeviceId ?? getOrCreateDeviceId();
}
