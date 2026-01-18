import { forwardRef } from 'react';
import { cn } from '@/shared/utils/cn';

type LineState = 'active' | 'past' | 'future';

interface LyricLineProps {
  text: string;
  state: LineState;
}

export const LyricLine = forwardRef<HTMLDivElement, LyricLineProps>(function LyricLine(
  { text, state },
  ref
) {
  return (
    <div
      ref={ref}
      className={cn(
        'px-6 py-2 text-center transition-all duration-300',
        state === 'active' && 'text-text-primary scale-105 text-lg font-semibold',
        state === 'past' && 'text-text-secondary opacity-50',
        state === 'future' && 'text-text-secondary opacity-70'
      )}
    >
      {text}
    </div>
  );
});
