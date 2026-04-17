import { BaseService } from '../api/base-api.service.js';
import type { GroupSnapshot, ScheduleAction, ScheduleUpdateResponse } from './group-sync.types.js';

export class GroupSyncService extends BaseService {
  async createGroup(name: string, trackId: string): Promise<{ id: string; joinCode: string }> {
    return this.client.postRequired<{ id: string; joinCode: string }>('/v1/groups', {
      name,
      trackId,
    });
  }

  async joinGroup(joinCode: string): Promise<GroupSnapshot> {
    return this.client.postRequired<GroupSnapshot>('/v1/groups/join', { joinCode });
  }

  async getSnapshot(groupId: string): Promise<GroupSnapshot> {
    return this.client.getRequired<GroupSnapshot>(`/v1/groups/${groupId}`);
  }

  async updateSchedule(
    groupId: string,
    expectedEpoch: number,
    action: ScheduleAction,
    trackId?: string,
    positionMs?: number,
    paused?: boolean
  ): Promise<ScheduleUpdateResponse> {
    return this.client.postRequired<ScheduleUpdateResponse>(`/v1/groups/${groupId}/schedule`, {
      expected_epoch: expectedEpoch,
      action,
      trackId: trackId ?? null,
      positionMs: positionMs ?? null,
      paused: paused ?? null,
    });
  }

  async heartbeat(groupId: string, rttMs?: number): Promise<void> {
    await this.client.post(`/v1/groups/${groupId}/heartbeat`, {
      rttMs: rttMs ?? null,
    });
  }

  async leaveGroup(groupId: string, deviceId: string): Promise<void> {
    await this.client.delete(`/v1/groups/${groupId}/members/${deviceId}`);
  }

  async endGroup(groupId: string): Promise<void> {
    await this.client.delete(`/v1/groups/${groupId}`);
  }
}
