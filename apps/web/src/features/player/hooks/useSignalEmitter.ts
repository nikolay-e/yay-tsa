import { useEffect, useRef } from 'react';
import type { PlaybackSignal, SignalType, SignalContext } from '@yay-tsa/core';
import { usePlayerStore } from '../stores/player.store';
import { useTimingStore } from '../stores/playback-timing.store';
import { useSessionStore } from '../stores/session-store';

const SKIP_EARLY_THRESHOLD = 0.25;
const SKIP_LATE_THRESHOLD = 0.75;
const VOLUME_DEBOUNCE_MS = 2000;
const PAUSE_LONG_THRESHOLD_MS = 30000;

function currentTimeOfDay(): string {
  const hour = new Date().getHours();
  if (hour < 6) return 'night';
  if (hour < 12) return 'morning';
  if (hour < 18) return 'afternoon';
  return 'evening';
}

function buildContext(
  positionPct: number,
  elapsedSec: number,
  autoplay: boolean,
  selectedByUser: boolean
): SignalContext {
  return {
    positionPct,
    elapsedSec,
    autoplay,
    selectedByUser,
    timeOfDay: currentTimeOfDay(),
  };
}

function createSignal(
  signalType: SignalType,
  trackId: string,
  context: SignalContext
): PlaybackSignal {
  return { signalType, trackId, context };
}

export function useSignalEmitter() {
  const previousTrackIdRef = useRef<string | null>(null);
  const previousTrackStartRef = useRef<number>(0);
  const volumeTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const pauseTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const wasPlayingRef = useRef(false);

  useEffect(() => {
    const sendSignal = useSessionStore.getState().sendSignal;

    const unsubCurrentTrack = usePlayerStore.subscribe(
      state => state.currentTrack,
      (currentTrack, previousTrack) => {
        if (!useSessionStore.getState().activeSession) return;

        if (previousTrack && previousTrackIdRef.current) {
          const { currentTime, duration } = useTimingStore.getState();
          const positionPct = duration > 0 ? currentTime / duration : 0;

          let skipType: SignalType | null = null;
          if (positionPct < SKIP_EARLY_THRESHOLD) {
            skipType = 'SKIP_EARLY';
          } else if (positionPct < SKIP_LATE_THRESHOLD) {
            skipType = 'SKIP_MID';
          } else if (positionPct < 0.95) {
            skipType = 'SKIP_LATE';
          } else {
            skipType = 'PLAY_COMPLETE';
          }

          void sendSignal(
            createSignal(
              skipType,
              previousTrackIdRef.current,
              buildContext(positionPct, currentTime, true, false)
            )
          );
        }

        if (currentTrack) {
          previousTrackIdRef.current = currentTrack.Id;
          previousTrackStartRef.current = Date.now();

          void sendSignal(
            createSignal('PLAY_START', currentTrack.Id, buildContext(0, 0, true, false))
          );
        } else {
          previousTrackIdRef.current = null;
        }
      }
    );

    const unsubVolume = usePlayerStore.subscribe(
      state => state.volume,
      () => {
        if (!useSessionStore.getState().activeSession) return;
        const trackId = previousTrackIdRef.current;
        if (!trackId) return;

        if (volumeTimerRef.current) {
          clearTimeout(volumeTimerRef.current);
        }
        volumeTimerRef.current = setTimeout(() => {
          const { currentTime, duration } = useTimingStore.getState();
          const positionPct = duration > 0 ? currentTime / duration : 0;
          void sendSignal(
            createSignal(
              'VOLUME_CHANGE',
              trackId,
              buildContext(positionPct, currentTime, false, true)
            )
          );
        }, VOLUME_DEBOUNCE_MS);
      }
    );

    const unsubPlaying = usePlayerStore.subscribe(
      state => state.isPlaying,
      isPlaying => {
        if (!useSessionStore.getState().activeSession) return;

        if (!isPlaying && wasPlayingRef.current) {
          pauseTimerRef.current = setTimeout(() => {
            const trackId = previousTrackIdRef.current;
            if (!trackId) return;
            const { currentTime, duration } = useTimingStore.getState();
            const positionPct = duration > 0 ? currentTime / duration : 0;
            void sendSignal(
              createSignal(
                'PAUSE_LONG',
                trackId,
                buildContext(positionPct, currentTime, false, true)
              )
            );
          }, PAUSE_LONG_THRESHOLD_MS);
        } else if (isPlaying && pauseTimerRef.current) {
          clearTimeout(pauseTimerRef.current);
          pauseTimerRef.current = undefined;
        }

        wasPlayingRef.current = isPlaying;
      }
    );

    return () => {
      unsubCurrentTrack();
      unsubVolume();
      unsubPlaying();
      if (volumeTimerRef.current) clearTimeout(volumeTimerRef.current);
      if (pauseTimerRef.current) clearTimeout(pauseTimerRef.current);
    };
  }, []);
}
