import { useState } from 'react';
import { X, Moon, Zap, Brain, Wind, PartyPopper, CloudRain } from 'lucide-react';
import type { SessionState } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';
import { useFocusTrap } from '@/shared/hooks/useFocusTrap';

interface SessionMoodSelectorProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (state: SessionState) => void;
  initialState?: SessionState;
}

const MOOD_PRESETS = [
  { tag: 'chill', label: 'Chill', icon: Moon, energy: 3, intensity: 3 },
  { tag: 'focus', label: 'Focus', icon: Brain, energy: 4, intensity: 5 },
  { tag: 'energize', label: 'Energize', icon: Zap, energy: 8, intensity: 7 },
  { tag: 'wind-down', label: 'Wind Down', icon: Wind, energy: 2, intensity: 2 },
  { tag: 'party', label: 'Party', icon: PartyPopper, energy: 9, intensity: 9 },
  { tag: 'melancholic', label: 'Melancholic', icon: CloudRain, energy: 3, intensity: 5 },
] as const;

const CONSTRAINT_OPTIONS = [
  'no vocals',
  'instrumental only',
  'acoustic',
  'no electronic',
  'low tempo',
];

const DEFAULT_STATE: SessionState = {
  energy: 5,
  intensity: 5,
  moodTags: [],
  attentionMode: 'active',
  constraints: [],
};

export function SessionMoodSelector({
  isOpen,
  onClose,
  onSubmit,
  initialState,
}: SessionMoodSelectorProps) {
  const dialogRef = useFocusTrap<HTMLDivElement>(isOpen);
  const [state, setState] = useState<SessionState>(initialState ?? DEFAULT_STATE);

  if (!isOpen) return null;

  const toggleMoodTag = (tag: string, energy: number, intensity: number) => {
    const isSelected = state.moodTags.includes(tag);
    if (isSelected) {
      setState(prev => ({
        ...prev,
        moodTags: prev.moodTags.filter(t => t !== tag),
      }));
    } else {
      setState(prev => ({
        ...prev,
        moodTags: [...prev.moodTags, tag],
        energy,
        intensity,
      }));
    }
  };

  const toggleConstraint = (constraint: string) => {
    setState(prev => ({
      ...prev,
      constraints: prev.constraints.includes(constraint)
        ? prev.constraints.filter(c => c !== constraint)
        : [...prev.constraints, constraint],
    }));
  };

  const handleSubmit = () => {
    onSubmit(state);
    onClose();
  };

  return (
    <div
      role="presentation"
      className="z-modal-backdrop fixed inset-0 flex items-end justify-center bg-black/50 sm:items-center"
      onClick={onClose}
      onKeyDown={e => e.key === 'Escape' && onClose()}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="mood-selector-title"
        className="bg-bg-secondary max-h-[85vh] w-full max-w-md overflow-y-auto rounded-t-2xl p-6 shadow-lg sm:rounded-2xl"
        onClick={e => e.stopPropagation()}
        onKeyDown={e => e.key !== 'Escape' && e.stopPropagation()}
      >
        <div className="mb-5 flex items-center justify-between">
          <h3 id="mood-selector-title" className="text-text-primary text-lg font-semibold">
            Set the Mood
          </h3>
          <button
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary p-1"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="mb-5">
          <div className="grid grid-cols-3 gap-2">
            {MOOD_PRESETS.map(preset => {
              const isSelected = state.moodTags.includes(preset.tag);
              const Icon = preset.icon;
              return (
                <button
                  key={preset.tag}
                  onClick={() => toggleMoodTag(preset.tag, preset.energy, preset.intensity)}
                  className={cn(
                    'flex flex-col items-center gap-1.5 rounded-xl p-3 transition-colors',
                    isSelected
                      ? 'bg-accent text-text-on-accent'
                      : 'bg-bg-tertiary text-text-secondary hover:bg-bg-hover hover:text-text-primary'
                  )}
                >
                  <Icon className="h-5 w-5" />
                  <span className="text-xs font-medium">{preset.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="mb-5">
          <span className="text-text-secondary mb-2 block text-sm font-medium">Attention Mode</span>
          <div className="grid grid-cols-2 gap-2">
            {(['background', 'active'] as const).map(mode => (
              <button
                key={mode}
                onClick={() => setState(prev => ({ ...prev, attentionMode: mode }))}
                className={cn(
                  'rounded-lg px-4 py-2.5 text-sm font-medium capitalize transition-colors',
                  state.attentionMode === mode
                    ? 'bg-accent text-text-on-accent'
                    : 'bg-bg-tertiary text-text-secondary hover:bg-bg-hover'
                )}
              >
                {mode}
              </button>
            ))}
          </div>
        </div>

        <div className="mb-5">
          <label
            htmlFor="energy-slider"
            className="text-text-secondary mb-2 flex items-center justify-between text-sm font-medium"
          >
            <span>Energy</span>
            <span className="text-text-primary font-semibold">{state.energy}</span>
          </label>
          <input
            id="energy-slider"
            type="range"
            min={1}
            max={10}
            value={state.energy}
            onChange={e => setState(prev => ({ ...prev, energy: Number(e.target.value) }))}
            className="accent-accent w-full"
          />
          <div className="text-text-tertiary mt-1 flex justify-between text-xs">
            <span>Calm</span>
            <span>Intense</span>
          </div>
        </div>

        <div className="mb-6">
          <span className="text-text-secondary mb-2 block text-sm font-medium">Constraints</span>
          <div className="flex flex-wrap gap-2">
            {CONSTRAINT_OPTIONS.map(constraint => (
              <button
                key={constraint}
                onClick={() => toggleConstraint(constraint)}
                className={cn(
                  'rounded-full px-3 py-1.5 text-xs font-medium transition-colors',
                  state.constraints.includes(constraint)
                    ? 'bg-accent text-text-on-accent'
                    : 'bg-bg-tertiary text-text-secondary hover:bg-bg-hover'
                )}
              >
                {constraint}
              </button>
            ))}
          </div>
        </div>

        <button
          onClick={handleSubmit}
          className="bg-accent text-text-on-accent hover:bg-accent-hover w-full rounded-xl py-3 text-sm font-semibold transition-colors"
        >
          {initialState ? 'Update Mood' : 'Start DJ Session'}
        </button>
      </div>
    </div>
  );
}
