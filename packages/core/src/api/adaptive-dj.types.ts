import type { SignalType } from '../generated/constants.js';
export type { SignalType };

export interface ListeningSession {
  id: string;
  userId: string;
  state: SessionState;
  startedAt: string;
  lastActivityAt?: string;
  sessionSummary?: string;
  isRadioMode: boolean;
  seedTrackId?: string;
  /** Honest degradation hint for radio: 'no_embedding' (seed unanalyzed) | 'sparse_neighbourhood' (few real neighbours) | null/undefined for normal radio. */
  degraded?: string | null;
}

export interface RadioSeed {
  trackId: string;
  name: string;
  artistName: string;
  albumName: string;
  albumId: string;
  imageTag: string;
}

export interface RadioSeedsResponse {
  seeds: RadioSeed[];
}

export interface SessionState {
  energy: number;
  intensity: number;
  moodTags: string[];
  attentionMode: 'background' | 'active';
  constraints: string[];
}

export interface AdaptiveQueueTrack {
  position: number;
  trackId: string;
  name: string;
  artistName: string;
  albumName: string;
  durationMs: number;
  status: 'QUEUED' | 'PLAYING' | 'PLAYED' | 'SKIPPED' | 'REMOVED';
  intentLabel?: string;
  addedReason?: string;
  features?: TrackFeatures;
}

export interface TrackFeatures {
  bpm: number;
  energy: number;
  valence: number;
  arousal: number;
  danceability: number;
}

export interface PlaybackSignal {
  signalType: SignalType;
  trackId: string;
  queueEntryId?: string;
  context: SignalContext;
}

export interface SignalContext {
  positionPct: number;
  elapsedSec: number;
  autoplay: boolean;
  selectedByUser: boolean;
  timeOfDay: string;
}

export interface UserPreferences {
  hardRules: Record<string, unknown>;
  softPrefs: Record<string, unknown>;
  djStyle: Record<string, unknown>;
  redLines: string[];
}

export interface RecommendedTrack {
  trackId: string;
  name: string;
  artistName: string;
  albumName: string;
  durationMs: number;
  score: number;
  source: string;
  features: TrackFeatures | null;
}
