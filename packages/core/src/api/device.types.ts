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

export interface TransferPayload {
  trackId?: string;
  trackName?: string;
  positionMs: number;
  paused: boolean;
  volumeLevel: number;
  sourceDeviceId: string;
  sourceSessionId: string;
  listeningSessionId?: string;
}

export interface RemoteCommand {
  type: RemoteCommandType;
  payload?: Record<string, unknown>;
}
