import { useEffect, useRef } from 'react';
import { usePlayerStore } from '../stores/player.store';
import { useSessionStore } from '../stores/session-store';
import { useTimingStore } from '../stores/playback-timing.store';

const REFILL_THRESHOLD_SEC = 300;
const CHECK_INTERVAL_MS = 30_000;

export function useDjAutoRefill() {
  const refillPendingRef = useRef(false);

  useEffect(() => {
    const interval = setInterval(() => {
      const session = useSessionStore.getState().activeSession;
      if (!session || refillPendingRef.current) return;

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
        void useSessionStore
          .getState()
          .refreshQueue()
          .finally(() => {
            refillPendingRef.current = false;
          });
      }
    }, CHECK_INTERVAL_MS);

    return () => clearInterval(interval);
  }, []);
}
