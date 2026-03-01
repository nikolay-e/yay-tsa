import { useEffect, useRef } from 'react';
import { Sparkles, Loader2, Power } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import {
  useActiveSession,
  useIsSessionStarting,
  useDjMode,
  useEnergyLevel,
  useSessionActions,
  type DjMode,
} from '../stores/session-store';

const DJ_MODES: { key: DjMode; label: string }[] = [
  { key: 'conservative', label: 'Safe' },
  { key: 'explorer', label: 'Mix' },
  { key: 'adventurer', label: 'New' },
];

function EnergyDots({ level, onChange }: { level: number; onChange: (level: number) => void }) {
  return (
    <div className="flex gap-1">
      {[1, 2, 3, 4, 5].map(n => (
        <button
          key={n}
          type="button"
          onClick={() => onChange(n)}
          className={cn(
            'h-2.5 w-2.5 rounded-full transition-colors',
            n <= level ? 'bg-accent' : 'bg-bg-tertiary hover:bg-bg-hover'
          )}
          aria-label={`Energy ${n}`}
        />
      ))}
    </div>
  );
}

export function DjModeControl() {
  const activeSession = useActiveSession();
  const isStarting = useIsSessionStarting();
  const djMode = useDjMode();
  const energyLevel = useEnergyLevel();
  const { startSession, endSession, setDjMode, setEnergyLevel, updateMood } = useSessionActions();
  const moodTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  const isActive = activeSession !== null;

  useEffect(() => {
    return () => {
      if (moodTimerRef.current) clearTimeout(moodTimerRef.current);
    };
  }, []);

  const debouncedUpdateMood = () => {
    if (moodTimerRef.current) clearTimeout(moodTimerRef.current);
    moodTimerRef.current = setTimeout(() => {
      void updateMood();
    }, 1000);
  };

  const handleToggle = () => {
    if (isActive) {
      void endSession();
    } else {
      void startSession();
    }
  };

  const handleModeChange = (mode: DjMode) => {
    setDjMode(mode);
    if (isActive) debouncedUpdateMood();
  };

  const handleEnergyChange = (level: number) => {
    setEnergyLevel(level);
    if (isActive) debouncedUpdateMood();
  };

  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <button
        type="button"
        onClick={handleToggle}
        disabled={isStarting}
        className={cn(
          'flex h-9 w-9 shrink-0 items-center justify-center rounded-lg transition-colors',
          isActive
            ? 'bg-accent text-text-on-accent'
            : 'bg-bg-tertiary text-text-secondary hover:bg-bg-hover'
        )}
        aria-label={isActive ? 'Stop DJ' : 'Start DJ'}
      >
        {isStarting ? (
          <Loader2 className="h-4 w-4 animate-spin" />
        ) : isActive ? (
          <Power className="h-4 w-4" />
        ) : (
          <Sparkles className="h-4 w-4" />
        )}
      </button>

      <div className="flex items-center gap-2">
        {DJ_MODES.map(mode => (
          <button
            key={mode.key}
            type="button"
            onClick={() => handleModeChange(mode.key)}
            className={cn(
              'rounded-md px-2 py-1 text-xs font-medium transition-colors',
              djMode === mode.key
                ? 'bg-accent/15 text-accent'
                : 'text-text-tertiary hover:text-text-secondary'
            )}
          >
            {mode.label}
          </button>
        ))}
      </div>

      <EnergyDots level={energyLevel} onChange={handleEnergyChange} />
    </div>
  );
}
