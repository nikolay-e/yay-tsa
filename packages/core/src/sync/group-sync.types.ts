export interface PlaybackSchedule {
  groupId: string;
  trackId: string;
  anchorServerMs: number;
  anchorPositionMs: number;
  isPaused: boolean;
  scheduleEpoch: number;
  nextTrackId?: string;
  nextTrackAnchorMs?: number;
}

export interface GroupMember {
  deviceId: string;
  userId: string;
  stale: boolean;
  reportedLatencyMs: number;
}

export interface GroupSnapshot {
  id: string;
  ownerId: string;
  joinCode: string;
  name: string;
  schedule: PlaybackSchedule;
  members: GroupMember[];
}

export interface ScheduleUpdateResponse {
  scheduleEpoch: number;
  schedule: PlaybackSchedule;
  serverTimeMs: number;
}

export type ScheduleAction = 'PLAY' | 'PAUSE' | 'SEEK' | 'NEXT' | 'PREV' | 'JUMP';
