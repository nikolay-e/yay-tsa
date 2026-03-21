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
        maskImage: 'linear-gradient(transparent 0%, black 15%, black 85%, transparent 100%)',
        WebkitMaskImage: 'linear-gradient(transparent 0%, black 15%, black 85%, transparent 100%)',
        scrollbarWidth: 'none',
      }}
    >
      <div style={{ height: '40%' }} aria-hidden="true" />
      {lines.map((line, i) => {
        const isActive = i === activeLineIndex;
        const dist = activeLineIndex >= 0 ? Math.abs(i - activeLineIndex) : 0;
        const opacity = activeLineIndex < 0 ? 0.7 : isActive ? 1 : Math.max(0.15, 1 - dist * 0.18);

        return (
          <div
            key={`${i}-${line.time}`}
            ref={isActive ? activeRef : undefined}
            className={cn(
              'px-6 py-2.5 text-center text-lg leading-relaxed transition-[opacity,color,font-weight] duration-500 ease-out',
              isActive ? 'text-text-primary font-bold' : 'text-text-secondary font-normal'
            )}
            style={{ opacity }}
          >
            {line.text || '\u00A0'}
          </div>
        );
      })}
      <div style={{ height: '40%' }} aria-hidden="true" />
    </div>
  );
});
