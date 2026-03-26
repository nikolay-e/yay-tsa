import { useRef, useEffect, useState, type CSSProperties } from 'react';
import { cn } from '@/shared/utils/cn';

type MarqueeTextProps = Readonly<{
  children: string;
  className?: string;
  speed?: number;
}>;

export function MarqueeText({ children, className, speed = 25 }: MarqueeTextProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLSpanElement>(null);
  const [shift, setShift] = useState(0);

  useEffect(() => {
    const container = containerRef.current;
    const text = textRef.current;
    if (!container || !text) return;

    const measure = () => {
      setShift(Math.max(0, text.scrollWidth - container.clientWidth));
    };

    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(container);
    return () => ro.disconnect();
  }, [children]);

  const isAnimating = shift > 0;
  // 30% pause at start and end, 40% scrolling — duration scales with distance
  const totalDuration = isAnimating ? (shift / speed / 0.4).toFixed(1) : '0';

  return (
    <div ref={containerRef} className={cn('overflow-hidden', className)}>
      <span
        ref={textRef}
        className="inline-block whitespace-nowrap"
        style={
          isAnimating
            ? ({
                '--marquee-shift': `-${shift}px`,
                animationName: 'marquee-text',
                animationDuration: `${totalDuration}s`,
                animationTimingFunction: 'linear',
                animationIterationCount: 'infinite',
              } as CSSProperties)
            : undefined
        }
      >
        {children}
      </span>
    </div>
  );
}
