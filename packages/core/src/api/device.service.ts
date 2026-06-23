import { MediaServerError } from '../internal/models/types.js';
import type { DeviceInfo, TransferLeaseResult } from './device.types.js';
import { BaseService } from './base-api.service.js';

// A web device never owns a server-side PlaybackSessionAggregate, so transferring playback to it
// 404s by design until that feature exists. Callers treat this as an expected "transfer
// unavailable" condition (calm UX, no error-level telemetry), distinct from a real failure.
export class TransferUnavailableError extends Error {
  constructor(message = 'Transfer unavailable') {
    super(message);
    this.name = 'TransferUnavailableError';
  }
}

interface DeviceSessionDto {
  sessionId: string;
  deviceId: string;
  userId?: string;
  deviceName?: string | null;
  clientName?: string | null;
  lastSeenAt: string;
  nowPlayingItemId?: string | null;
  nowPlayingItemName?: string | null;
  positionMs?: number;
  playbackState?: string;
}

// 3 missed 15s heartbeats — a device quiet longer than this is treated as offline.
const ONLINE_WINDOW_MS = 45_000;

export class DeviceService extends BaseService {
  async listDevices(): Promise<DeviceInfo[]> {
    const rows = await this.client.get<DeviceSessionDto[]>('/v1/me/devices');
    if (!rows) return [];
    const now = Date.now();
    return rows.map(row => {
      const lastSeenMs = Date.parse(row.lastSeenAt);
      return {
        sessionId: row.sessionId,
        deviceId: row.deviceId,
        deviceName: row.deviceName ?? undefined,
        clientName: row.clientName ?? undefined,
        lastUpdate: row.lastSeenAt,
        isOnline: Number.isFinite(lastSeenMs) && now - lastSeenMs < ONLINE_WINDOW_MS,
        nowPlayingItemId: row.nowPlayingItemId ?? undefined,
        nowPlayingItemName: row.nowPlayingItemName ?? undefined,
        positionMs: row.positionMs,
        isPaused: row.playbackState ? row.playbackState !== 'PLAYING' : undefined,
      };
    });
  }

  async heartbeat(): Promise<void> {
    // Best-effort: a 15s background heartbeat whose failures the caller ignores. A transient
    // "Failed to fetch" (page navigating, network blip) must not be logged as an error and
    // forwarded to telemetry — it is expected noise, not a client bug.
    await this.client.post('/v1/me/devices/heartbeat', undefined, undefined, true);
  }

  async sendCommand(
    deviceSessionId: string,
    type: string,
    payload?: Record<string, unknown>
  ): Promise<void> {
    await this.client.post(`/v1/me/devices/${deviceSessionId}/command`, {
      type,
      ...payload,
    });
  }

  async transferLease(sourceSessionId: string, toDeviceId: string): Promise<TransferLeaseResult> {
    try {
      return await this.client.postRequired<TransferLeaseResult>(
        `/v1/me/devices/${sourceSessionId}/transfer`,
        { toDeviceId }
      );
    } catch (error) {
      if (error instanceof MediaServerError && error.statusCode === 404) {
        throw new TransferUnavailableError();
      }
      throw error;
    }
  }

  buildSseUrl(path: string): string {
    const serverUrl = this.client.getServerUrl();
    const token = this.client.getToken();
    const params = new URLSearchParams({ api_key: token ?? '' });
    return `${serverUrl}${path}?${params}`;
  }
}
