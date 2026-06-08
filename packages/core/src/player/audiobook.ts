import { ticksToSeconds } from '../internal/config/constants.js';
import type { AudioItem, BaseItem } from '../internal/models/types.js';

export const AUDIOBOOK_GENRES = ['audiobook', 'audiobooks'];

export function isAudiobook(item: Pick<BaseItem, 'Genres' | 'MediaType'>): boolean {
  if (item.MediaType === 'Book') return true;
  return (item.Genres ?? []).some(genre => AUDIOBOOK_GENRES.includes(genre.trim().toLowerCase()));
}

export function getSmartRewindMs(pausedForMs: number): number {
  if (pausedForMs < 60_000) return 0;
  if (pausedForMs < 10 * 60_000) return 5_000;
  if (pausedForMs < 60 * 60_000) return 15_000;
  return 30_000;
}

export function resumePositionSeconds(item: Pick<AudioItem, 'UserData'>): number {
  const ticks = item.UserData?.PlaybackPositionTicks ?? 0;
  return ticks > 0 ? ticksToSeconds(ticks) : 0;
}
