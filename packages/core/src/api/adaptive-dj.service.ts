import type {
  ListeningSession,
  SessionState,
  AdaptiveQueueTrack,
  PlaybackSignal,
  UserPreferences,
  RecommendedTrack,
  RadioSeedsResponse,
} from './adaptive-dj.types.js';
import { BaseService } from './base-api.service.js';

export class AdaptiveDjService extends BaseService {
  async startSession(state: SessionState, seedTrackId?: string): Promise<ListeningSession> {
    const userId = this.requireAuth();
    return this.client.postRequired<ListeningSession>('/v1/sessions', {
      userId,
      state,
      seed_track_id: seedTrackId ?? undefined,
    });
  }

  async updateSessionState(sessionId: string, state: SessionState): Promise<void> {
    await this.client.request(`/v1/sessions/${sessionId}/state`, {
      method: 'PATCH',
      body: JSON.stringify({ state }),
    });
  }

  async endSession(sessionId: string): Promise<void> {
    await this.client.delete(`/v1/sessions/${sessionId}`);
  }

  async getQueue(sessionId: string): Promise<AdaptiveQueueTrack[]> {
    const result = await this.client.get<{ tracks: AdaptiveQueueTrack[] }>(
      `/v1/sessions/${sessionId}/queue`
    );
    return result?.tracks ?? [];
  }

  async refreshQueue(sessionId: string): Promise<void> {
    await this.client.post(`/v1/sessions/${sessionId}/queue/refresh`);
  }

  async sendSignal(sessionId: string, signal: PlaybackSignal): Promise<void> {
    await this.client.post(`/v1/sessions/${sessionId}/signals`, {
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

  async getActiveSession(): Promise<ListeningSession | null> {
    const result = await this.client.get<ListeningSession>('/v1/sessions/active');
    return result ?? null;
  }

  async getPreferences(userId: string): Promise<UserPreferences> {
    return this.client.getRequired<UserPreferences>(`/v1/users/${userId}/preferences`);
  }

  async updatePreferences(userId: string, prefs: UserPreferences): Promise<void> {
    await this.client.request(`/v1/users/${userId}/preferences`, {
      method: 'PUT',
      body: JSON.stringify(prefs),
    });
  }

  async getRadioSeeds(): Promise<RadioSeedsResponse | null> {
    const result = await this.client.get<RadioSeedsResponse>('/v1/recommend/radio/seeds');
    return result ?? null;
  }

  async searchByText(query: string, limit = 20): Promise<RecommendedTrack[]> {
    const params = new URLSearchParams({ q: query, limit: String(limit) });
    const result = await this.client.get<RecommendedTrack[]>(
      `/v1/recommend/search?${params.toString()}`
    );
    return result ?? [];
  }
}
