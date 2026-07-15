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

// The track item may carry stale UserData (clients cache it on album/search/queue surfaces),
// so the device's own local write-through competes with it — except for finished
// chapters, whose ticks are deliberately zeroed so a re-listen starts clean.
export function audiobookResumeSeconds(
  item: Pick<AudioItem, 'UserData'>,
  localResume: { positionMs: number } | null
): number {
  const localSeconds = localResume && !item.UserData?.Played ? localResume.positionMs / 1000 : 0;
  return Math.max(resumePositionSeconds(item), localSeconds);
}
