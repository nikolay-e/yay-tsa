export interface ListeningSession {
  id: string;
  userId: string;
  state: SessionState;
  startedAt: string;
  lastActivityAt?: string;
  sessionSummary?: string;
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

export type SignalType =
  | 'PLAY_START'
  | 'PLAY_COMPLETE'
  | 'SKIP_EARLY'
  | 'SKIP_MID'
  | 'SKIP_LATE'
  | 'SEEK_BACK'
  | 'SEEK_FORWARD'
  | 'QUEUE_JUMP'
  | 'REPEAT_TRACK'
  | 'VOLUME_CHANGE'
  | 'PAUSE_LONG'
  | 'FAVORITE_TOGGLE'
  | 'MANUAL_ADD'
  | 'MANUAL_REMOVE'
  | 'SESSION_MOOD_CHANGE';

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
