import { useRef, useEffect, useLayoutEffect } from 'react';
import { findActiveLineIndex } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { LYRICS_TEST_IDS } from '@/shared/testing/test-ids';
import { useTimingStore } from '../stores/playback-timing.store';

type LyricsLine = Readonly<{ time: number; text: string }>;

type LyricsScrollerProps = Readonly<{
  lines: LyricsLine[];
  activeLineIndex: number;
  isTimeSynced: boolean;
}>;

const DECAY_RATE = 8;
const USER_SCROLL_PAUSE_MS = 5000;

const maskStyle = {
  maskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
  WebkitMaskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
  scrollbarWidth: 'none' as const,
};

export function LyricsScroller({ lines, activeLineIndex, isTimeSynced }: LyricsScrollerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const linesRef = useRef(lines);
  const offsetsRef = useRef<number[]>([]);
  const currentY = useRef(0);
  const targetY = useRef(0);
  const rafId = useRef(0);
  const lastFrameTime = useRef(0);
  const userScrollUntil = useRef(0);

  useEffect(() => {
    linesRef.current = lines;
    currentY.current = 0;
    targetY.current = 0;
    userScrollUntil.current = 0;
  }, [lines]);

  const cacheOffsets = () => {
    const inner = innerRef.current;
    const container = containerRef.current;
    if (!inner || !container) return;

    const half = container.clientHeight / 2;
    const children = inner.children;
    const offsets: number[] = [];
    const start = isTimeSynced ? 1 : 0;
    const end = isTimeSynced ? children.length - 1 : children.length;
    for (let i = start; i < end; i++) {
      const el = children[i] as HTMLElement;
      offsets.push(el.offsetTop - half + el.offsetHeight / 2);
    }
    offsetsRef.current = offsets;
  };

  useLayoutEffect(() => {
    cacheOffsets();

    const container = containerRef.current;
    if (!container) return;
    const observer = new ResizeObserver(cacheOffsets);
    observer.observe(container);
    return () => observer.disconnect();
  }, [lines, isTimeSynced]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !isTimeSynced) return;

    const pauseAutoScroll = () => {
      userScrollUntil.current = performance.now() + USER_SCROLL_PAUSE_MS;
    };

    container.addEventListener('touchstart', pauseAutoScroll, { passive: true });
    container.addEventListener('wheel', pauseAutoScroll, { passive: true });
    return () => {
      container.removeEventListener('touchstart', pauseAutoScroll);
      container.removeEventListener('wheel', pauseAutoScroll);
    };
  }, [isTimeSynced]);

  useEffect(() => {
    if (!isTimeSynced) {
      cancelAnimationFrame(rafId.current);
      const container = containerRef.current;
      if (container) container.scrollTop = 0;
      return;
    }

    const container = containerRef.current;
    if (!container) return;

    let initialized = false;

    const animate = (timestamp: number) => {
      if (performance.now() < userScrollUntil.current) {
        currentY.current = container.scrollTop;
        lastFrameTime.current = 0;
        rafId.current = 0;
        return;
      }

      const dt = lastFrameTime.current
        ? Math.min((timestamp - lastFrameTime.current) / 1000, 0.1)
        : 0.016;
      lastFrameTime.current = timestamp;

      const diff = targetY.current - currentY.current;
      if (Math.abs(diff) < 0.3) {
        currentY.current = targetY.current;
        container.scrollTop = currentY.current;
        lastFrameTime.current = 0;
        rafId.current = 0;
        return;
      }
      currentY.current += diff * (1 - Math.exp(-DECAY_RATE * dt));
      container.scrollTop = currentY.current;
      rafId.current = requestAnimationFrame(animate);
    };

    const scheduleAnimate = () => {
      if (!rafId.current) {
        rafId.current = requestAnimationFrame(animate);
      }
    };

    const unsubscribe = useTimingStore.subscribe(state => {
      const currentLines = linesRef.current;
      const offsets = offsetsRef.current;
      if (currentLines.length === 0 || offsets.length === 0) return;

      const { currentTime } = state;
      const idx = findActiveLineIndex(currentLines, currentTime);
      if (idx < 0 || idx >= offsets.length) return;

      const baseOffset = offsets[idx]!;
      let target = baseOffset;

      const currentLine = currentLines[idx];
      const nextLine = currentLines[idx + 1];
      const nextOffset = offsets[idx + 1];
      if (currentLine && nextLine && nextOffset !== undefined) {
        const duration = nextLine.time - currentLine.time;
        if (duration > 0) {
          const progress = Math.min(1, (currentTime - currentLine.time) / duration);
          target = baseOffset + (nextOffset - baseOffset) * progress;
        }
      }

      targetY.current = target;

      if (performance.now() < userScrollUntil.current) return;

      if (!initialized) {
        currentY.current = target;
        container.scrollTop = target;
        initialized = true;
      }
      scheduleAnimate();
    });

    return () => {
      unsubscribe();
      cancelAnimationFrame(rafId.current);
    };
  }, [isTimeSynced]);

  return (
    <div ref={containerRef} className="h-full overflow-y-auto" style={maskStyle}>
      <div ref={innerRef}>
        {isTimeSynced && <div style={{ height: '45%' }} aria-hidden="true" />}
        {(() => {
          const hasActive = isTimeSynced && activeLineIndex >= 0;
          return lines.map((line, i) => {
            const dist = hasActive ? Math.abs(i - activeLineIndex) : 0;

            let opacity: number;
            let textClass: string;

            if (!isTimeSynced || !hasActive) {
              opacity = 0.85;
              textClass = 'text-text-primary';
            } else if (dist <= 4) {
              opacity = 0.95 - dist * 0.12;
              textClass = 'text-accent';
            } else {
              opacity = Math.max(0.06, 0.3 - (dist - 5) * 0.05);
              textClass = 'text-text-secondary';
            }

            return (
              <div
                key={`${i}-${line.time}`}
                data-testid={LYRICS_TEST_IDS.LINE}
                className={cn(
                  'px-6 py-3 text-center text-3xl leading-relaxed transition-[opacity,color] duration-500 ease-out',
                  textClass
                )}
                style={{ opacity }}
              >
                {line.text || '\u00A0'}
              </div>
            );
          });
        })()}
        {isTimeSynced && <div style={{ height: '45%' }} aria-hidden="true" />}
      </div>
    </div>
  );
}
