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

export function RadioFilterPanel({ filters, onChange }: RadioFilterPanelProps) {
  const { data: available } = useRadioFilters();

  const toggleMood = (mood: string) => {
    onChange({ ...filters, mood: filters.mood === mood ? undefined : mood });
  };

  const setLanguage = (lang: string | undefined) => {
    onChange({ ...filters, language: lang });
  };

  const setEnergy = (min: number | undefined, max: number | undefined) => {
    onChange({ ...filters, minEnergy: min, maxEnergy: max });
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

      {/* Energy range */}
      <div>
        <label className="text-text-secondary mb-1.5 block text-xs font-medium uppercase tracking-wide">
          Energy
        </label>
        <div className="flex items-center gap-2">
          <span className="text-text-secondary text-xs">Low</span>
          <input
            type="range"
            min="1"
            max="10"
            value={filters.minEnergy ?? 1}
            onChange={e => setEnergy(Number(e.target.value), filters.maxEnergy)}
            className="accent-accent flex-1"
          />
          <input
            type="range"
            min="1"
            max="10"
            value={filters.maxEnergy ?? 10}
            onChange={e => setEnergy(filters.minEnergy, Number(e.target.value))}
            className="accent-accent flex-1"
          />
          <span className="text-text-secondary text-xs">High</span>
        </div>
        <div className="text-text-secondary mt-0.5 text-center text-xs">
          {filters.minEnergy ?? 1} - {filters.maxEnergy ?? 10}
        </div>
      </div>
    </div>
  );
}
