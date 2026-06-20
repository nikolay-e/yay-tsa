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

// host = only the owner may change the schedule; everyone = any member may.
// Optional so a backend that has not yet shipped the field defaults to host.
export type GroupControlMode = 'host' | 'everyone';

export interface GroupSnapshot {
  id: string;
  ownerId: string;
  joinCode: string;
  name: string;
  schedule: PlaybackSchedule;
  members: GroupMember[];
  controlMode?: GroupControlMode;
}

export interface ScheduleUpdateResponse {
  scheduleEpoch: number;
  schedule: PlaybackSchedule;
  serverTimeMs: number;
}

export type ScheduleAction = 'PLAY' | 'PAUSE' | 'SEEK' | 'NEXT' | 'PREV' | 'JUMP';
