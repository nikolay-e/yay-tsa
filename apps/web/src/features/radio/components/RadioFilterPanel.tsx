import { type RadioFilters } from '@yay-tsa/core';
import { useRadioFilters } from '../hooks/useRadio';

interface RadioFilterPanelProps {
  filters: RadioFilters;
  onChange: (filters: RadioFilters) => void;
}

const MOOD_LABELS: Record<string, string> = {
  happy: 'Happy',
  sad: 'Sad',
  calm: 'Calm',
  energetic: 'Energetic',
  aggressive: 'Aggressive',
  romantic: 'Romantic',
  melancholic: 'Melancholic',
  nostalgic: 'Nostalgic',
};

const LANGUAGE_LABELS: Record<string, string> = {
  en: 'English',
  ru: 'Russian',
  de: 'German',
  fr: 'French',
  es: 'Spanish',
  it: 'Italian',
  ja: 'Japanese',
  ko: 'Korean',
  zh: 'Chinese',
  instrumental: 'Instrumental',
};

const ENERGY_LEVELS = [
  { label: 'Low', min: 1, max: 4 },
  { label: 'Medium', min: 4, max: 7 },
  { label: 'High', min: 7, max: 10 },
] as const;

export function RadioFilterPanel({ filters, onChange }: RadioFilterPanelProps) {
  const { data: available } = useRadioFilters();

  const toggleMood = (mood: string) => {
    onChange({ ...filters, mood: filters.mood === mood ? undefined : mood });
  };

  const setLanguage = (lang: string | undefined) => {
    onChange({ ...filters, language: lang });
  };

  const currentEnergyLevel = ENERGY_LEVELS.find(
    l => l.min === filters.minEnergy && l.max === filters.maxEnergy,
  );

  const toggleEnergy = (level: (typeof ENERGY_LEVELS)[number]) => {
    if (currentEnergyLevel === level) {
      onChange({ ...filters, minEnergy: undefined, maxEnergy: undefined });
    } else {
      onChange({ ...filters, minEnergy: level.min, maxEnergy: level.max });
    }
  };

  return (
    <div className="border-border/50 mt-3 space-y-3 border-t pt-3">
      {/* Mood pills */}
      {available?.moods && available.moods.length > 0 && (
        <div>
          <label className="text-text-secondary mb-1.5 block text-xs font-medium uppercase tracking-wide">
            Mood
          </label>
          <div className="flex flex-wrap gap-1.5">
            {available.moods.map(mood => (
              <button
                key={mood}
                onClick={() => toggleMood(mood)}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  filters.mood === mood
                    ? 'bg-accent text-text-on-accent'
                    : 'bg-bg-secondary hover:bg-bg-hover text-text-primary'
                }`}
              >
                {MOOD_LABELS[mood] ?? mood}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Language dropdown */}
      {available?.languages && available.languages.length > 0 && (
        <div>
          <label className="text-text-secondary mb-1.5 block text-xs font-medium uppercase tracking-wide">
            Language
          </label>
          <select
            value={filters.language ?? ''}
            onChange={e => setLanguage(e.target.value || undefined)}
            className="bg-bg-secondary border-border text-text-primary rounded-md border px-3 py-1.5 text-sm"
          >
            <option value="">All languages</option>
            {available.languages.map(lang => (
              <option key={lang} value={lang}>
                {LANGUAGE_LABELS[lang] ?? lang}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Energy level pills */}
      {available?.moods && available.moods.length > 0 && (
        <div>
          <label className="text-text-secondary mb-1.5 block text-xs font-medium uppercase tracking-wide">
            Energy
          </label>
          <div className="flex flex-wrap gap-1.5">
            {ENERGY_LEVELS.map(level => (
              <button
                key={level.label}
                onClick={() => toggleEnergy(level)}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  currentEnergyLevel === level
                    ? 'bg-accent text-text-on-accent'
                    : 'bg-bg-secondary hover:bg-bg-hover text-text-primary'
                }`}
              >
                {level.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
