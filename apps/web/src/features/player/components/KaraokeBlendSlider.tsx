import { cn } from '@/shared/utils/cn';

type KaraokeBlendSliderProps = Readonly<{
  active: boolean;
  value: number;
  disabled: boolean;
  onChange: (value: number) => void;
  className?: string;
}>;

const TICKS = [
  { value: 0, label: 'Karaoke' },
  { value: 0.3, label: 'Guide vocals' },
  { value: 1, label: 'Full vocals' },
];

export function KaraokeBlendSlider({
  active,
  value,
  disabled,
  onChange,
  className,
}: KaraokeBlendSliderProps) {
  if (!active) return null;

  const percent = Math.round(value * 100);

  return (
    <div
      className={cn('flex min-w-40 flex-col gap-1', className)}
      data-testid="karaoke-blend-slider"
    >
      <div className="flex items-center gap-2">
        <input
          type="range"
          min={0}
          max={100}
          step={1}
          value={percent}
          disabled={disabled}
          onChange={e => onChange(Number(e.target.value) / 100)}
          onDoubleClick={() => onChange(0)}
          aria-label="Vocal blend"
          aria-valuetext={`${percent}% vocals`}
          className="accent-accent h-11 flex-1 cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
        />
        <span className="text-text-secondary w-9 shrink-0 text-right text-xs tabular-nums">
          {percent}%
        </span>
      </div>
      <div className="text-text-tertiary flex justify-between text-[10px] leading-none">
        {TICKS.map(tick => (
          <button
            key={tick.value}
            type="button"
            disabled={disabled}
            onClick={() => onChange(tick.value)}
            className="hover:text-text-primary transition-colors disabled:cursor-not-allowed"
          >
            {tick.label}
          </button>
        ))}
      </div>
    </div>
  );
}
