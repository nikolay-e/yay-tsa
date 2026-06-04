import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MediaSessionManager } from './media-session';

type ActionHandler = ((details: { seekTime?: number }) => void) | null;

function fakeMediaSession() {
  const handlers: Record<string, ActionHandler> = {};
  return {
    handlers,
    metadata: null as unknown,
    playbackState: 'none' as 'none' | 'paused' | 'playing',
    setActionHandler(action: string, handler: ActionHandler) {
      handlers[action] = handler;
    },
    setPositionState: vi.fn(),
  };
}

describe('MediaSessionManager', () => {
  let ms: ReturnType<typeof fakeMediaSession>;

  beforeEach(() => {
    ms = fakeMediaSession();
    vi.stubGlobal('navigator', { mediaSession: ms });
    vi.stubGlobal(
      'MediaMetadata',
      class {
        constructor(init: unknown) {
          Object.assign(this as object, init as object);
        }
      }
    );
  });

  it('registers play/pause/seek/stop and clears next/previous + seek-skip when not provided', () => {
    new MediaSessionManager().setActionHandlers({
      onPlay: () => {},
      onPause: () => {},
      onSeek: () => {},
    });
    expect(typeof ms.handlers.play).toBe('function');
    expect(typeof ms.handlers.pause).toBe('function');
    expect(typeof ms.handlers.seekto).toBe('function');
    expect(typeof ms.handlers.stop).toBe('function');
    // Absent handlers are explicitly cleared (null), never left stale.
    expect(ms.handlers.nexttrack).toBeNull();
    expect(ms.handlers.previoustrack).toBeNull();
    // iOS would otherwise render these as ±10s skip buttons in place of track controls.
    expect(ms.handlers.seekforward).toBeNull();
    expect(ms.handlers.seekbackward).toBeNull();
  });

  it('wires next/previous when provided via setActionHandlers', () => {
    const onNext = vi.fn();
    const onPrevious = vi.fn();
    new MediaSessionManager().setActionHandlers({ onNext, onPrevious });
    expect(typeof ms.handlers.nexttrack).toBe('function');
    expect(typeof ms.handlers.previoustrack).toBe('function');
    ms.handlers.nexttrack?.({});
    ms.handlers.previoustrack?.({});
    expect(onNext).toHaveBeenCalledOnce();
    expect(onPrevious).toHaveBeenCalledOnce();
  });

  it('setNavigationHandlers enables nexttrack and removes previoustrack when null', () => {
    const onNext = vi.fn();
    new MediaSessionManager().setNavigationHandlers(onNext, null);
    expect(typeof ms.handlers.nexttrack).toBe('function');
    expect(ms.handlers.previoustrack).toBeNull();
    ms.handlers.nexttrack?.({});
    expect(onNext).toHaveBeenCalledOnce();
  });

  it('setNavigationHandlers(null, fn) removes nexttrack at end of queue', () => {
    const onPrevious = vi.fn();
    new MediaSessionManager().setNavigationHandlers(null, onPrevious);
    expect(ms.handlers.nexttrack).toBeNull();
    expect(typeof ms.handlers.previoustrack).toBe('function');
  });

  it('seekto forwards seekTime to onSeek', () => {
    const onSeek = vi.fn();
    new MediaSessionManager().setActionHandlers({ onSeek });
    ms.handlers.seekto?.({ seekTime: 42 });
    expect(onSeek).toHaveBeenCalledWith(42);
  });

  it('updateMetadata and setPlaybackState write through to mediaSession', () => {
    const m = new MediaSessionManager();
    m.updateMetadata({ title: 'T', artist: 'A', album: 'Al', artwork: 'x' });
    expect((ms.metadata as { title: string }).title).toBe('T');
    m.setPlaybackState('playing');
    expect(ms.playbackState).toBe('playing');
  });

  it('does not throw when the platform rejects an unsupported action', () => {
    ms.setActionHandler = () => {
      throw new Error('Unsupported action');
    };
    expect(() =>
      new MediaSessionManager().setNavigationHandlers(
        () => {},
        () => {}
      )
    ).not.toThrow();
  });
});
