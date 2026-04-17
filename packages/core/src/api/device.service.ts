import type { DeviceInfo, TransferPayload } from './device.types.js';
import { BaseService } from './base-api.service.js';

export class DeviceService extends BaseService {
  async listDevices(): Promise<DeviceInfo[]> {
    const result = await this.client.get<DeviceInfo[]>('/v1/me/devices');
    return result ?? [];
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

  async transferPlayback(sourceSessionId: string): Promise<TransferPayload> {
    return this.client.postRequired<TransferPayload>(`/v1/me/devices/${sourceSessionId}/transfer`);
  }

  buildSseUrl(path: string): string {
    const serverUrl = this.client.getServerUrl();
    const token = this.client.getToken();
    const params = new URLSearchParams({ api_key: token ?? '' });
    return `${serverUrl}${path}?${params}`;
  }
}
