import { useRef, useEffect, useLayoutEffect } from 'react';
import { findActiveLineIndex } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useTimingStore } from '../stores/playback-timing.store';

type LyricsLine = Readonly<{ time: number; text: string }>;

type LyricsScrollerProps = Readonly<{
  lines: LyricsLine[];
  activeLineIndex: number;
  isTimeSynced: boolean;
}>;

export function LyricsScroller({ lines, activeLineIndex, isTimeSynced }: LyricsScrollerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const linesRef = useRef(lines);
  const offsetsRef = useRef<number[]>([]);
  const currentY = useRef(0);
  const targetY = useRef(0);
  const rafId = useRef(0);
  const lastFrameTime = useRef(0);

  useEffect(() => {
    linesRef.current = lines;
    currentY.current = 0;
    targetY.current = 0;
  }, [lines]);

  const cacheOffsets = () => {
    const inner = innerRef.current;
    const container = containerRef.current;
    if (!inner || !container) return;

    const half = container.clientHeight / 2;
    const children = inner.children;
    const offsets: number[] = [];
    for (let i = 1; i < children.length - 1; i++) {
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
  }, [lines]);

  useEffect(() => {
    if (!isTimeSynced) {
      cancelAnimationFrame(rafId.current);
      const inner = innerRef.current;
      if (inner) inner.style.transform = '';
      return;
    }

    const inner = innerRef.current;
    if (!inner) return;

    let initialized = false;
    const DECAY_RATE = 8;

    const animate = (timestamp: number) => {
      const dt = lastFrameTime.current
        ? Math.min((timestamp - lastFrameTime.current) / 1000, 0.1)
        : 0.016;
      lastFrameTime.current = timestamp;

      const diff = targetY.current - currentY.current;
      if (Math.abs(diff) < 0.3) {
        currentY.current = targetY.current;
        inner.style.transform = `translateY(${-currentY.current}px)`;
        lastFrameTime.current = 0;
        rafId.current = 0;
        return;
      }
      currentY.current += diff * (1 - Math.exp(-DECAY_RATE * dt));
      inner.style.transform = `translateY(${-currentY.current}px)`;
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

      if (!initialized) {
        currentY.current = target;
        targetY.current = target;
        inner.style.transform = `translateY(${-target}px)`;
        initialized = true;
        scheduleAnimate();
      } else {
        targetY.current = target;
        scheduleAnimate();
      }
    });

    return () => {
      unsubscribe();
      cancelAnimationFrame(rafId.current);
    };
  }, [isTimeSynced]);

  return (
    <div
      ref={containerRef}
      className="h-full overflow-hidden"
      style={{
        maskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
        WebkitMaskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
      }}
    >
      <div ref={innerRef} className="will-change-transform">
        <div style={{ height: '45%' }} aria-hidden="true" />
        {(() => {
          const hasActive = isTimeSynced && activeLineIndex >= 0;
          return lines.map((line, i) => {
            const isActive = hasActive && i === activeLineIndex;
            const dist = hasActive ? Math.abs(i - activeLineIndex) : 0;

            let opacity: number;
            let textClass: string;
            let shadow: string;

            if (!isTimeSynced || !hasActive) {
              opacity = 0.85;
              textClass = 'text-text-primary font-normal';
              shadow = 'none';
            } else if (isActive) {
              opacity = 1;
              textClass = 'text-accent font-bold';
              shadow =
                '0 0 24px var(--color-accent), 0 0 48px color-mix(in srgb, var(--color-accent) 40%, transparent)';
            } else if (dist <= 1) {
              opacity = 0.7;
              textClass = 'text-accent font-normal';
              shadow = '0 0 12px color-mix(in srgb, var(--color-accent) 25%, transparent)';
            } else if (dist <= 2) {
              opacity = 0.45;
              textClass = 'text-text-primary font-normal';
              shadow = 'none';
            } else {
              opacity = Math.max(0.06, 0.3 - (dist - 3) * 0.08);
              textClass = 'text-text-secondary font-normal';
              shadow = 'none';
            }

            return (
              <div
                key={`${i}-${line.time}`}
                className={cn(
                  'px-6 py-3 text-center text-3xl leading-relaxed transition-all duration-500 ease-out',
                  textClass
                )}
                style={{ opacity, textShadow: shadow }}
              >
                {line.text || '\u00A0'}
              </div>
            );
          });
        })()}
        <div style={{ height: '45%' }} aria-hidden="true" />
      </div>
    </div>
  );
}
