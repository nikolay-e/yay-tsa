import type { PlaybackSchedule } from './group-sync.types.js';

export const DRIFT_DEAD_ZONE_MS = 30;
export const DRIFT_HARD_SEEK_MS = 150;
export const HARD_SEEK_COOLDOWN_MS = 2000;

export function computeExpectedPositionMs(schedule: PlaybackSchedule, serverNowMs: number): number {
  if (schedule.isPaused) return schedule.anchorPositionMs;
  const elapsed = serverNowMs - schedule.anchorServerMs;
  return schedule.anchorPositionMs + Math.max(0, elapsed);
}

export function driftCorrectionRate(driftMs: number): number {
  return Math.max(0.98, Math.min(1.02, 1 - driftMs / 5000));
}
