import type { DeviceInfo, TransferLeaseResult } from './device.types.js';
import { BaseService } from './base-api.service.js';

interface DeviceSessionDto {
  sessionId: string;
  deviceId: string;
  userId?: string;
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
    await this.client.post('/v1/me/devices/heartbeat');
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
    return this.client.postRequired<TransferLeaseResult>(
      `/v1/me/devices/${sourceSessionId}/transfer`,
      { toDeviceId }
    );
  }

  buildSseUrl(path: string): string {
    const serverUrl = this.client.getServerUrl();
    const token = this.client.getToken();
    const params = new URLSearchParams({ api_key: token ?? '' });
    return `${serverUrl}${path}?${params}`;
  }
}
