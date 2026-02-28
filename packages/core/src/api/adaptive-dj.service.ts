import type {
  ListeningSession,
  SessionState,
  AdaptiveQueueTrack,
  PlaybackSignal,
  UserPreferences,
} from './adaptive-dj.types.js';
import { BaseService } from './base-api.service.js';

export class AdaptiveDjService extends BaseService {
  async startSession(state: SessionState): Promise<ListeningSession> {
    const userId = this.requireAuth();
    return this.client.postRequired<ListeningSession>('/api/v1/sessions', {
      userId,
      state,
    });
  }

  async updateSessionState(sessionId: string, state: SessionState): Promise<void> {
    await this.client.request(`/api/v1/sessions/${sessionId}/state`, {
      method: 'PATCH',
      body: JSON.stringify({ state }),
    });
  }

  async endSession(sessionId: string): Promise<void> {
    await this.client.delete(`/api/v1/sessions/${sessionId}`);
  }

  async getQueue(sessionId: string): Promise<AdaptiveQueueTrack[]> {
    const result = await this.client.get<{ tracks: AdaptiveQueueTrack[] }>(
      `/api/v1/sessions/${sessionId}/queue`
    );
    return result?.tracks ?? [];
  }

  async refreshQueue(sessionId: string): Promise<void> {
    await this.client.post(`/api/v1/sessions/${sessionId}/queue/refresh`);
  }

  async sendSignal(sessionId: string, signal: PlaybackSignal): Promise<void> {
    await this.client.post(`/api/v1/sessions/${sessionId}/signals`, {
      signal_type: signal.signalType,
      track_id: signal.trackId,
      queue_entry_id: signal.queueEntryId,
      context: signal.context
        ? {
            position_pct: signal.context.positionPct,
            elapsed_sec: signal.context.elapsedSec,
            autoplay: signal.context.autoplay,
            selected_by_user: signal.context.selectedByUser,
            time_of_day: signal.context.timeOfDay,
          }
        : undefined,
    });
  }

  async getPreferences(userId: string): Promise<UserPreferences> {
    return this.client.getRequired<UserPreferences>(`/api/v1/users/${userId}/preferences`);
  }

  async updatePreferences(userId: string, prefs: UserPreferences): Promise<void> {
    await this.client.request(`/api/v1/users/${userId}/preferences`, {
      method: 'PUT',
      body: JSON.stringify(prefs),
    });
  }
}
