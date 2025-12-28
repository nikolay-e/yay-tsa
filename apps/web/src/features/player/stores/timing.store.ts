import { create } from 'zustand';

interface TimingState {
  currentTime: number;
  duration: number;
}

interface TimingActions {
  updateTiming: (time: number, duration: number) => void;
  reset: () => void;
}

type TimingStore = TimingState & TimingActions;

let rafId: number | null = null;
let pendingUpdate: TimingState | null = null;

export const useTimingStore = create<TimingStore>((set) => ({
  currentTime: 0,
  duration: 0,

  updateTiming: (time: number, duration: number) => {
    pendingUpdate = { currentTime: time, duration };

    rafId ??= requestAnimationFrame(() => {
      if (pendingUpdate) {
        set(pendingUpdate);
        pendingUpdate = null;
      }
      rafId = null;
    });
  },

  reset: () => {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    pendingUpdate = null;
    set({ currentTime: 0, duration: 0 });
  },
}));

export const useCurrentTime = () => useTimingStore((state) => state.currentTime);
export const useDuration = () => useTimingStore((state) => state.duration);
