import { describe, it, expect } from 'vitest';
import type { AudioEngine } from '@yay-tsa/platform';
import { PreloadManager, PRELOAD_START_S } from './preload-manager';

function engineRecorder() {
  const preloadCalls: string[] = [];
  const loaded = new Set<string>();
  const engine = {
    preload: (url: string) => {
      preloadCalls.push(url);
      loaded.add(url);
      return Promise.resolve();
    },
    isPreloaded: (url: string) => loaded.has(url),
  } as unknown as AudioEngine;
  return { engine, preloadCalls, loaded };
}

describe('PreloadManager deferred start (bandwidth stagger)', () => {
  it('prepare alone never touches the engine', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    expect(preloadCalls).toEqual([]);
    expect(pm.isReady('t1')).toBe(false);
  });

  it('tick before the watermark does not start the download', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(PRELOAD_START_S - 1, 200);
    expect(preloadCalls).toEqual([]);
  });

  it('tick past the watermark starts the download exactly once', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(PRELOAD_START_S, 200);
    pm.tick(PRELOAD_START_S + 5, 200);
    expect(preloadCalls).toEqual(['/stream/t1']);
    expect(pm.isReady('t1')).toBe(true);
  });

  it('short tracks use the halfway point instead of the fixed watermark', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(14, 30);
    expect(preloadCalls).toEqual([]);
    pm.tick(15, 30);
    expect(preloadCalls).toEqual(['/stream/t1']);
  });

  it('unknown duration defers the download', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(100, 0);
    pm.tick(100, Number.NaN);
    expect(preloadCalls).toEqual([]);
  });

  it('re-preparing a different track drops the started one and restarts at the watermark', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(PRELOAD_START_S, 200);
    pm.prepare('t2', '/stream/t2');
    expect(pm.isReady('t1')).toBe(false);
    pm.tick(PRELOAD_START_S + 1, 200);
    expect(preloadCalls).toEqual(['/stream/t1', '/stream/t2']);
    expect(pm.isReady('t2')).toBe(true);
  });

  it('consume returns the started url and clears all state', () => {
    const { engine } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.tick(PRELOAD_START_S, 200);
    expect(pm.consume()).toBe('/stream/t1');
    expect(pm.isReady('t1')).toBe(false);
    expect(pm.getStreamUrl()).toBeNull();
  });

  it('invalidate cancels intent so later ticks stay idle', () => {
    const { engine, preloadCalls } = engineRecorder();
    const pm = new PreloadManager(engine);
    pm.prepare('t1', '/stream/t1');
    pm.invalidate();
    pm.tick(PRELOAD_START_S + 10, 200);
    expect(preloadCalls).toEqual([]);
  });
});
