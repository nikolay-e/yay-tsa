import { useEffect, useRef } from 'react';
import { usePlayerStore } from '../stores/player.store';
import { useSessionStore } from '../stores/session-store';
import { useTimingStore } from '../stores/playback-timing.store';

const REFILL_THRESHOLD_SEC = 600;
const BASE_INTERVAL_MS = 15_000;
const MAX_BACKOFF_MS = 300_000;

export function useDjAutoRefill() {
  const refillPendingRef = useRef(false);
  const consecutiveErrorsRef = useRef(0);
  const lastAttemptRef = useRef(0);

  useEffect(() => {
    const interval = setInterval(() => {
      const { activeSession, error } = useSessionStore.getState();
      if (!activeSession || refillPendingRef.current) return;

      const backoffMs = Math.min(
        BASE_INTERVAL_MS * Math.pow(2, consecutiveErrorsRef.current),
        MAX_BACKOFF_MS
      );
      const now = Date.now();
      if (now - lastAttemptRef.current < backoffMs) return;

      if (error) {
        consecutiveErrorsRef.current++;
        lastAttemptRef.current = now;
        return;
      }

      const { queueItems, queueIndex } = usePlayerStore.getState();
      if (queueItems.length === 0 || queueIndex < 0) return;

      const { currentTime, duration } = useTimingStore.getState();
      const currentRemaining = Math.max(0, duration - currentTime);

      let totalRemainingSec = currentRemaining;
      for (let i = queueIndex + 1; i < queueItems.length; i++) {
        const item = queueItems[i];
        if (item?.RunTimeTicks) totalRemainingSec += item.RunTimeTicks / 10_000_000;
      }

      if (totalRemainingSec < REFILL_THRESHOLD_SEC) {
        refillPendingRef.current = true;
        lastAttemptRef.current = now;
        useSessionStore
          .getState()
          .refreshQueue()
          .then(() => {
            consecutiveErrorsRef.current = 0;
          })
          .catch(() => {
            consecutiveErrorsRef.current++;
          })
          .finally(() => {
            refillPendingRef.current = false;
          });
      }
    }, BASE_INTERVAL_MS);

    return () => clearInterval(interval);
  }, []);
}
