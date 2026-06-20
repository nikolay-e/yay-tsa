import type { RemoteCommandType } from '../generated/constants.js';
export type { RemoteCommandType };

// The /v1/me/devices presence projection only carries identity + last-seen; rich
// now-playing fields arrive later via the device-state SSE patch (or stay absent).
// `isOnline` is derived client-side from `lastUpdate` recency, never sent by the server.
export interface DeviceInfo {
  sessionId: string;
  deviceId: string;
  isOnline: boolean;
  lastUpdate: string;
  deviceName?: string;
  clientName?: string;
  nowPlayingItemId?: string;
  nowPlayingItemName?: string;
  positionMs?: number;
  isPaused?: boolean;
  volumeLevel?: number;
}

export interface DeviceStateEvent {
  // Absent on a lease release (no controlling device); the PWA then full-refetches
  // the device list instead of patching a single device in place.
  deviceId?: string;
  nowPlayingItemId?: string;
  nowPlayingItemName?: string;
  positionMs: number;
  isPaused: boolean;
  timestamp: number;
}

export interface TransferLeaseResult {
  sessionId: string;
  version: number;
  deviceId?: string;
  currentEntryId?: string;
  positionMs: number;
  playbackState: string;
}

// STOP is delivered to a device that lost its lease (e.g. on transfer-away); it is
// not a user-issued command type, so it lives alongside the RemoteCommandType union
// rather than inside it.
export type RemoteCommandWireType = RemoteCommandType | 'STOP';

export interface RemoteCommand {
  type: RemoteCommandWireType;
  // Set by the backend when a command targets one specific device on the SSE fan-out;
  // a receiving client acts only when it matches its own device id.
  targetDeviceId?: string;
  payload?: Record<string, unknown>;
}
