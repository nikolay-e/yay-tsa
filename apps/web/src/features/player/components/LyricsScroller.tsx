import { useRef, useEffect, memo } from 'react';
import { findActiveLineIndex } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useTimingStore } from '../stores/playback-timing.store';

type LyricsLine = Readonly<{ time: number; text: string }>;

type LyricsScrollerProps = Readonly<{
  lines: LyricsLine[];
  activeLineIndex: number;
  isTimeSynced: boolean;
}>;

export const LyricsScroller = memo(function LyricsScroller({
  lines,
  activeLineIndex,
  isTimeSynced,
}: LyricsScrollerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const linesRef = useRef(lines);

  useEffect(() => {
    linesRef.current = lines;
  }, [lines]);

  useEffect(() => {
    if (!isTimeSynced) return;

    const container = containerRef.current;
    if (!container) return;

    let initialized = false;

    const unsubscribe = useTimingStore.subscribe(state => {
      const currentLines = linesRef.current;
      if (currentLines.length === 0) return;

      const { currentTime } = state;
      const idx = findActiveLineIndex(currentLines, currentTime);
      if (idx < 0) return;

      const currentEl = container.children[idx + 1] as HTMLElement;
      if (!currentEl) return;

      const half = container.clientHeight / 2;
      const currentCenter = currentEl.offsetTop - half + currentEl.offsetHeight / 2;

      let target = currentCenter;

      const currentLine = currentLines[idx];
      const nextLine = currentLines[idx + 1];
      if (currentLine && nextLine) {
        const nextEl = container.children[idx + 2] as HTMLElement;
        if (nextEl) {
          const nextCenter = nextEl.offsetTop - half + nextEl.offsetHeight / 2;
          const duration = nextLine.time - currentLine.time;
          if (duration > 0) {
            const progress = Math.min(1, (currentTime - currentLine.time) / duration);
            target = currentCenter + (nextCenter - currentCenter) * progress;
          }
        }
      }

      if (!initialized) {
        container.scrollTop = target;
        initialized = true;
      } else {
        container.scrollTop += (target - container.scrollTop) * 0.12;
      }
    });

    return unsubscribe;
  }, [isTimeSynced]);

  return (
    <div
      ref={containerRef}
      className="h-full overflow-y-auto [&::-webkit-scrollbar]:hidden"
      style={{
        maskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
        WebkitMaskImage: 'linear-gradient(transparent 0%, black 12%, black 88%, transparent 100%)',
        scrollbarWidth: 'none',
      }}
    >
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
  );
});
