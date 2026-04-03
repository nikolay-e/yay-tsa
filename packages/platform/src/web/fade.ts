import { easeInOutQuad } from '../shared/easing.js';

const FADE_INTERVAL_MS = 16;

export function createFade(
  fromLevel: number,
  toLevel: number,
  durationMs: number,
  useEasing: boolean,
  setVolume: (v: number) => void
): { promise: Promise<void>; cancel: () => void } {
  const startTime = Date.now();
  const startVolume = Math.max(0, Math.min(1, fromLevel));
  const endVolume = Math.max(0, Math.min(1, toLevel));
  let cancelled = false;
  let intervalId: ReturnType<typeof setInterval> | null = null;

  const cancel = () => {
    cancelled = true;
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
  };

  const promise = new Promise<void>(resolve => {
    setVolume(startVolume);

    intervalId = setInterval(() => {
      if (cancelled) {
        resolve();
        return;
      }

      const elapsed = Date.now() - startTime;
      const progress = Math.min(elapsed / durationMs, 1);

      const easedProgress = useEasing ? easeInOutQuad(progress) : progress;

      const currentVolume = startVolume + (endVolume - startVolume) * easedProgress;
      setVolume(currentVolume);

      if (progress >= 1) {
        if (intervalId) {
          clearInterval(intervalId);
          intervalId = null;
        }
        resolve();
      }
    }, FADE_INTERVAL_MS);
  });

  return { promise, cancel };
}
