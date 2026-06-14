import type { RemoteCommandType } from '../generated/constants.js';
export type { RemoteCommandType };

export interface DeviceInfo {
  sessionId: string;
  deviceId: string;
  deviceName: string;
  clientName: string;
  isOnline: boolean;
  lastUpdate: string;
  nowPlayingItemId?: string;
  nowPlayingItemName?: string;
  positionMs: number;
  isPaused: boolean;
  volumeLevel: number;
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
