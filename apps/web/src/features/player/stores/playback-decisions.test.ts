import { describe, it, expect } from 'vitest';
import { PlaybackQueue, type AudioItem } from '@yay-tsa/core';
import { navAvailability, previousAction, endedAction } from './playback-decisions';

const track = (id: string): AudioItem =>
  ({ Id: id, Name: id, Type: 'Audio' }) as unknown as AudioItem;

function queueAt(ids: string[], index: number): PlaybackQueue {
  const q = new PlaybackQueue();
  q.setQueue(ids.map(track), index);
  return q;
}

describe('navAvailability (lock-screen next/previous controls)', () => {
  it('empty queue exposes neither control', () => {
    expect(navAvailability(new PlaybackQueue())).toEqual({ hasNext: false, hasPrevious: false });
  });

  it('mid-queue exposes both', () => {
    expect(navAvailability(queueAt(['a', 'b', 'c'], 1))).toEqual({
      hasNext: true,
      hasPrevious: true,
    });
  });

  it('last track with repeat off drops next (handler must be cleared, not a dead button)', () => {
    const q = queueAt(['a', 'b'], 1);
    expect(navAvailability(q)).toEqual({ hasNext: false, hasPrevious: true });
  });

  it('last track with repeat all keeps next (wraps around)', () => {
    const q = queueAt(['a', 'b'], 1);
    q.setRepeatMode('all');
    expect(navAvailability(q).hasNext).toBe(true);
  });

  it('repeat one keeps next pointing at the following track, not the current one', () => {
    const q = queueAt(['a', 'b'], 0);
    q.setRepeatMode('one');
    expect(navAvailability(q).hasNext).toBe(true);
    expect(q.peekNext({ manual: true })?.Id).toBe('b');
  });

  it('first track still exposes previous (previous() restarts it)', () => {
    expect(navAvailability(queueAt(['a', 'b'], 0)).hasPrevious).toBe(true);
  });
});

describe('previousAction (restart vs step back)', () => {
  it('restarts the current track when more than the threshold in', () => {
    expect(previousAction(5, true)).toBe('restart');
  });
  it('steps to the previous track near the start', () => {
    expect(previousAction(1, true)).toBe('previous');
  });
  it('restarts near the start when there is no previous track', () => {
    expect(previousAction(1, false)).toBe('restart');
  });
  it('honours a custom restart threshold', () => {
    expect(previousAction(4, true, 5)).toBe('previous');
  });
});

describe('endedAction (single auto-advance path)', () => {
  it('repeats the current track on repeat-one', () => {
    expect(endedAction('one', null)).toEqual({ type: 'repeat-one' });
  });
  it('advances to the next track', () => {
    expect(endedAction('off', track('b'))).toEqual({ type: 'advance', nextId: 'b' });
  });
  it('stops at the end of the queue with no next', () => {
    expect(endedAction('off', null)).toEqual({ type: 'stop' });
  });
});
