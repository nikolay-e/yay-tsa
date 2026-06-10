import React, { useLayoutEffect, useRef } from 'react';
import { formatSeconds } from '@/shared/utils/time';
import { useTimingStore } from '../stores/playback-timing.store';

interface SeekBarProps {
  onSeek: (seconds: number) => void;
}

function formatTimeText(time: number, total: number): string {
  return `${formatSeconds(time)} of ${formatSeconds(total)}`;
}

export function SeekBar({ onSeek }: Readonly<SeekBarProps>) {
  const sliderRef = useRef<HTMLInputElement>(null);
  const isDragging = useRef(false);
  const pendingValue = useRef<number | null>(null);

  useLayoutEffect(() => {
    const apply = (currentTime: number, duration: number) => {
      if (!sliderRef.current || isDragging.current) return;
      sliderRef.current.value = String(currentTime);
      sliderRef.current.max = String(duration || 1);
      const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
      sliderRef.current.style.background = `linear-gradient(to right, var(--color-accent) ${progress}%, var(--color-bg-tertiary) ${progress}%)`;
      sliderRef.current.setAttribute('aria-valuetext', formatTimeText(currentTime, duration));
    };

    // Seed from the live store on mount so a remount (opening the full player, route change) shows
    // the real position immediately instead of 0 — critical while paused, when no timeupdate fires
    // to correct it. The store is a singleton, so its position survives navigation.
    const { currentTime, duration } = useTimingStore.getState();
    apply(currentTime, duration);

    return useTimingStore.subscribe(state => apply(state.currentTime, state.duration));
  }, []);

  const updateSliderVisual = (value: number) => {
    if (!sliderRef.current) return;
    const max = Number.parseFloat(sliderRef.current.max) || 1;
    const progress = max > 0 ? (value / max) * 100 : 0;
    sliderRef.current.style.background = `linear-gradient(to right, var(--color-accent) ${progress}%, var(--color-bg-tertiary) ${progress}%)`;
    sliderRef.current.setAttribute('aria-valuetext', formatTimeText(value, max));
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = Number.parseFloat(e.target.value);
    pendingValue.current = value;
    updateSliderVisual(value);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLInputElement>) => {
    isDragging.current = true;
    pendingValue.current = null;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      // Synthetic pointer events (e.g. from tests) may have invalid pointerIds
    }
  };

  const handlePointerUp = () => {
    isDragging.current = false;
    if (pendingValue.current !== null) {
      onSeek(pendingValue.current);
      pendingValue.current = null;
    }
  };

  const handleKeyUp = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(e.key)) {
      onSeek(Number.parseFloat(e.currentTarget.value));
    }
  };

  return (
    <input
      ref={sliderRef}
      data-testid="seek-slider"
      type="range"
      min={0}
      max={1}
      step={0.1}
      defaultValue={0}
      onChange={handleChange}
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerUp}
      onKeyUp={handleKeyUp}
      aria-label="Seek"
      aria-valuetext="0:00 of 0:00"
      className="bg-bg-tertiary accent-accent h-1 w-full cursor-pointer appearance-none"
    />
  );
}

export function TimeDisplay() {
  const currentTimeRef = useRef<HTMLSpanElement>(null);
  const totalTimeRef = useRef<HTMLSpanElement>(null);
  const lastDisplayedSecond = useRef(-1);
  const lastDisplayedDuration = useRef(-1);

  useLayoutEffect(() => {
    const apply = (currentTime: number, duration: number) => {
      const currentSecond = Math.floor(currentTime);
      const durationSecond = Math.floor(duration);
      if (currentSecond !== lastDisplayedSecond.current) {
        lastDisplayedSecond.current = currentSecond;
        if (currentTimeRef.current) currentTimeRef.current.textContent = formatSeconds(currentTime);
      }
      if (durationSecond !== lastDisplayedDuration.current) {
        lastDisplayedDuration.current = durationSecond;
        if (totalTimeRef.current) totalTimeRef.current.textContent = formatSeconds(duration);
      }
    };

    // Seed from the live store on mount so the time text reflects the real position immediately
    // after a remount, even while paused (no timeupdate would otherwise fire to correct 0:00).
    const { currentTime, duration } = useTimingStore.getState();
    apply(currentTime, duration);

    return useTimingStore.subscribe(state => apply(state.currentTime, state.duration));
  }, []);

  return (
    <span className="text-text-tertiary hidden text-xs tabular-nums md:inline">
      <span ref={currentTimeRef} data-testid="current-time">
        0:00
      </span>{' '}
      /{' '}
      <span ref={totalTimeRef} data-testid="total-time">
        0:00
      </span>
    </span>
  );
}
