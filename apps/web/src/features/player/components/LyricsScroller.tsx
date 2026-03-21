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
      {lines.map((line, i) => {
        const isActive = i === activeLineIndex;
        const dist = activeLineIndex >= 0 ? Math.abs(i - activeLineIndex) : 0;
        const opacity =
          activeLineIndex < 0 ? 0.5 : isActive ? 1 : Math.max(0.08, 0.5 - dist * 0.12);

        return (
          <div
            key={`${i}-${line.time}`}
            ref={isActive ? activeRef : undefined}
            className={cn(
              'px-6 py-3 text-center text-xl leading-relaxed transition-all duration-500 ease-out',
              isActive ? 'text-accent font-bold' : 'text-text-secondary font-normal'
            )}
            style={{
              opacity,
              textShadow: isActive
                ? '0 0 24px var(--color-accent), 0 0 48px color-mix(in srgb, var(--color-accent) 40%, transparent)'
                : 'none',
            }}
          >
            {line.text || '\u00A0'}
          </div>
        );
      })}
      <div style={{ height: '45%' }} aria-hidden="true" />
    </div>
  );
});
