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
  deviceId: string;
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

export interface RemoteCommand {
  type: RemoteCommandType;
  payload?: Record<string, unknown>;
}
