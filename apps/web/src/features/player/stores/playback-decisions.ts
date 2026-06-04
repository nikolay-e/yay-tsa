import { type PlaybackQueue, type AudioItem } from '@yay-tsa/core';

export type RepeatMode = 'off' | 'one' | 'all';

/**
 * Pure playback-control decisions shared by the player store and the Media Session lock-screen
 * handlers. Kept side-effect-free so the lock-screen / auto-advance behaviour is unit-testable
 * without an audio engine or a browser.
 */

export interface NavAvailability {
  hasNext: boolean;
  hasPrevious: boolean;
}

/**
 * What the lock-screen next/previous controls should expose for the current queue.
 * - next is available only when there genuinely is a following track (so the OS drops the button
 *   at the end of the queue with repeat off, instead of showing a dead control).
 * - previous is available whenever a track is loaded: previousAction() restarts the current track
 *   when >threshold in, and steps back otherwise.
 */
export function navAvailability(queue: PlaybackQueue): NavAvailability {
  return {
    hasNext: queue.peekNext() !== null,
    hasPrevious: !queue.isEmpty(),
  };
}

export type PreviousAction = 'restart' | 'previous';

/**
 * Lock-screen / UI "previous" semantics: restart the current track if we're more than
 * restartThresholdSec in (matches native players); otherwise step to the previous track, falling
 * back to restart when there is no previous track.
 */
export function previousAction(
  currentTimeSec: number,
  hasPrevious: boolean,
  restartThresholdSec = 3
): PreviousAction {
  if (currentTimeSec > restartThresholdSec) return 'restart';
  return hasPrevious ? 'previous' : 'restart';
}

export type EndedAction =
  | { type: 'repeat-one' }
  | { type: 'advance'; nextId: string }
  | { type: 'stop' };

/**
 * What to do when a track ends (the single auto-advance path, independent of any UI being mounted).
 */
export function endedAction(repeatMode: RepeatMode, next: AudioItem | null): EndedAction {
  if (repeatMode === 'one') return { type: 'repeat-one' };
  if (!next) return { type: 'stop' };
  return { type: 'advance', nextId: next.Id };
}
