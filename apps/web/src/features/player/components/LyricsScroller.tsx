import { useRef, useEffect, memo } from 'react';
import { cn } from '@/shared/utils/cn';

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
  const activeRef = useRef<HTMLDivElement>(null);
  const rafId = useRef(0);
  const lastIndex = useRef(-1);

  useEffect(() => {
    lastIndex.current = -1;
  }, [lines]);

  useEffect(() => {
    if (!isTimeSynced || activeLineIndex < 0) return;
    if (activeLineIndex === lastIndex.current) return;
    lastIndex.current = activeLineIndex;

    const container = containerRef.current;
    const line = activeRef.current;
    if (!container || !line) return;

    cancelAnimationFrame(rafId.current);

    const target = line.offsetTop - container.clientHeight / 2 + line.offsetHeight / 2;
    const start = container.scrollTop;
    const delta = target - start;
    if (Math.abs(delta) < 1) return;

    const duration = 600;
    const t0 = performance.now();

    function step(now: number) {
      const p = Math.min((now - t0) / duration, 1);
      container!.scrollTop = start + delta * (1 - (1 - p) ** 3);
      if (p < 1) rafId.current = requestAnimationFrame(step);
    }

    rafId.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(rafId.current);
  }, [activeLineIndex, isTimeSynced]);

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
              ref={isActive ? activeRef : undefined}
              className={cn(
                'px-6 py-3 text-center text-xl leading-relaxed transition-all duration-500 ease-out',
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
