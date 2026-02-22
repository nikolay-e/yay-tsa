import React, { memo, useEffect, useRef, useCallback } from 'react';
import { formatSeconds } from '@/shared/utils/time';
import { useTimingStore } from '../stores/playback-timing.store';

interface SeekBarProps {
  onSeek: (seconds: number) => void;
}

function formatTimeText(time: number, total: number): string {
  return `${formatSeconds(time)} of ${formatSeconds(total)}`;
}

export const SeekBar = memo(function SeekBar({ onSeek }: SeekBarProps) {
  const sliderRef = useRef<HTMLInputElement>(null);
  const isDragging = useRef(false);

  useEffect(() => {
    return useTimingStore.subscribe(state => {
      const { currentTime, duration } = state;

      if (sliderRef.current && !isDragging.current) {
        sliderRef.current.value = String(currentTime);
        sliderRef.current.max = String(duration || 1);
        const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
        sliderRef.current.style.background = `linear-gradient(to right, var(--color-accent) ${progress}%, var(--color-bg-tertiary) ${progress}%)`;
        sliderRef.current.setAttribute('aria-valuetext', formatTimeText(currentTime, duration));
      }
    });
  }, []);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onSeek(parseFloat(e.target.value));
    },
    [onSeek]
  );

  const handlePointerDown = useCallback((e: React.PointerEvent<HTMLInputElement>) => {
    isDragging.current = true;
    e.currentTarget.setPointerCapture(e.pointerId);
  }, []);

  const handlePointerUp = useCallback(() => {
    isDragging.current = false;
  }, []);

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
      aria-label="Seek"
      aria-valuetext="0:00 of 0:00"
      className="bg-bg-tertiary accent-accent h-1 w-full cursor-pointer appearance-none"
    />
  );
});

export const TimeDisplay = memo(function TimeDisplay() {
  const currentTimeRef = useRef<HTMLSpanElement>(null);
  const totalTimeRef = useRef<HTMLSpanElement>(null);
  const lastDisplayedSecond = useRef(-1);
  const lastDisplayedDuration = useRef(-1);

  useEffect(() => {
    return useTimingStore.subscribe(state => {
      const currentSecond = Math.floor(state.currentTime);
      const durationSecond = Math.floor(state.duration);
      if (currentSecond !== lastDisplayedSecond.current) {
        lastDisplayedSecond.current = currentSecond;
        if (currentTimeRef.current)
          currentTimeRef.current.textContent = formatSeconds(state.currentTime);
      }
      if (durationSecond !== lastDisplayedDuration.current) {
        lastDisplayedDuration.current = durationSecond;
        if (totalTimeRef.current) totalTimeRef.current.textContent = formatSeconds(state.duration);
      }
    });
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
});
