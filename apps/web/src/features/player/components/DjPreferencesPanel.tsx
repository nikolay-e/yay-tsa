import { useEffect, useState } from 'react';
import { Plus, X, Loader2, ChevronDown, ChevronUp } from 'lucide-react';
import { AdaptiveDjService, type UserPreferences } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { toast } from '@/shared/ui/Toast';
import { cn } from '@/shared/utils/cn';

const DJ_STYLES = [
  {
    key: 'smooth',
    label: 'Smooth DJ',
    description: 'Familiar favorites with smooth transitions.',
  },
  {
    key: 'adventurous',
    label: 'Discovery DJ',
    description: 'Only plays music you have never heard before.',
  },
] as const;

type DjStyleKey = (typeof DJ_STYLES)[number]['key'];

interface FormState {
  maxArtistConsecutive: number;
  noRepeatHours: number;
  djStyle: DjStyleKey;
  redLines: string[];
}

const DEFAULT_FORM: FormState = {
  maxArtistConsecutive: 2,
  noRepeatHours: 4,
  djStyle: 'smooth',
  redLines: [],
};

function prefsToForm(prefs: UserPreferences): FormState {
  return {
    maxArtistConsecutive:
      (prefs.hardRules['maxArtistConsecutive'] as number) ?? DEFAULT_FORM.maxArtistConsecutive,
    noRepeatHours: (prefs.hardRules['noRepeatHours'] as number) ?? DEFAULT_FORM.noRepeatHours,
    djStyle: ((prefs.djStyle['preset'] as string) ?? DEFAULT_FORM.djStyle) as DjStyleKey,
    redLines: prefs.redLines ?? [],
  };
}

function formToPrefs(form: FormState): UserPreferences {
  return {
    hardRules: {
      maxArtistConsecutive: form.maxArtistConsecutive,
      noRepeatHours: form.noRepeatHours,
    },
    softPrefs: {},
    djStyle: { preset: form.djStyle },
    redLines: form.redLines,
  };
}

export function DjPreferencesPanel() {
  const client = useAuthStore(state => state.client);
  const userId = useAuthStore(state => state.userId);
  const [form, setForm] = useState<FormState>(DEFAULT_FORM);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [newRedLine, setNewRedLine] = useState('');
  const [isExpanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!client || !userId) return;

    const service = new AdaptiveDjService(client);
    void service
      .getPreferences(userId)
      .then(prefs => {
        setForm(prefsToForm(prefs));
        setIsLoading(false);
      })
      .catch(() => {
        setIsLoading(false);
      });
  }, [client, userId]);

  const handleSave = async () => {
    if (!client || !userId) return;

    setIsSaving(true);
    try {
      const service = new AdaptiveDjService(client);
      await service.updatePreferences(userId, formToPrefs(form));
      toast.add('success', 'DJ preferences saved');
    } catch {
      toast.add('error', 'Failed to save preferences');
    } finally {
      setIsSaving(false);
    }
  };

  const addRedLine = () => {
    const trimmed = newRedLine.trim();
    if (!trimmed || form.redLines.includes(trimmed)) return;
    setForm(prev => ({ ...prev, redLines: [...prev.redLines, trimmed] }));
    setNewRedLine('');
  };

  const removeRedLine = (line: string) => {
    setForm(prev => ({
      ...prev,
      redLines: prev.redLines.filter(r => r !== line),
    }));
  };

  return (
    <div className="border-border border-t">
      <button
        onClick={() => setExpanded(!isExpanded)}
        className="text-text-secondary hover:text-text-primary flex w-full items-center justify-between px-4 py-3 text-sm font-medium transition-colors"
      >
        <span>DJ Preferences</span>
        {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
      </button>

      {isExpanded && (
        <div className="px-4 pb-4">
          {isLoading ? (
            <div className="flex justify-center py-4">
              <Loader2 className="text-accent h-5 w-5 animate-spin" />
            </div>
          ) : (
            <div className="space-y-5">
              <div>
                <span className="text-text-secondary mb-2 block text-xs font-medium tracking-wide uppercase">
                  DJ Style
                </span>
                <div className="space-y-1.5">
                  {DJ_STYLES.map(style => (
                    <button
                      key={style.key}
                      onClick={() => setForm(prev => ({ ...prev, djStyle: style.key }))}
                      className={cn(
                        'w-full rounded-lg border px-3 py-2.5 text-left transition-colors',
                        form.djStyle === style.key
                          ? 'border-accent bg-accent/5'
                          : 'bg-bg-tertiary border-border hover:bg-bg-hover'
                      )}
                    >
                      <div
                        className={cn(
                          'text-sm font-medium',
                          form.djStyle === style.key ? 'text-accent' : 'text-text-primary'
                        )}
                      >
                        {style.label}
                      </div>
                      <div className="text-text-secondary text-xs">{style.description}</div>
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex gap-3">
                <div className="flex-1">
                  <label
                    htmlFor="max-artist-inline"
                    className="text-text-secondary mb-1 block text-xs"
                  >
                    Max consecutive per artist
                  </label>
                  <input
                    id="max-artist-inline"
                    type="number"
                    min={1}
                    max={10}
                    value={form.maxArtistConsecutive}
                    onChange={e =>
                      setForm(prev => ({
                        ...prev,
                        maxArtistConsecutive: Number(e.target.value),
                      }))
                    }
                    onBlur={e => {
                      const val = Number(e.target.value);
                      if (Number.isNaN(val)) return;
                      setForm(prev => ({
                        ...prev,
                        maxArtistConsecutive: Math.max(1, Math.min(10, val)),
                      }));
                    }}
                    className="bg-bg-tertiary border-border text-text-primary w-full rounded-lg border px-3 py-2 text-sm"
                  />
                </div>
                <div className="flex-1">
                  <label
                    htmlFor="no-repeat-inline"
                    className="text-text-secondary mb-1 block text-xs"
                  >
                    No-repeat window (hours)
                  </label>
                  <input
                    id="no-repeat-inline"
                    type="number"
                    min={0}
                    max={72}
                    value={form.noRepeatHours}
                    onChange={e =>
                      setForm(prev => ({ ...prev, noRepeatHours: Number(e.target.value) }))
                    }
                    onBlur={e => {
                      const val = Number(e.target.value);
                      if (Number.isNaN(val)) return;
                      setForm(prev => ({ ...prev, noRepeatHours: Math.max(0, Math.min(72, val)) }));
                    }}
                    className="bg-bg-tertiary border-border text-text-primary w-full rounded-lg border px-3 py-2 text-sm"
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor="never-play-input"
                  className="text-text-secondary mb-1 block text-xs"
                >
                  Never play
                </label>
                <div className="flex gap-2">
                  <input
                    id="never-play-input"
                    type="text"
                    value={newRedLine}
                    onChange={e => setNewRedLine(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addRedLine()}
                    placeholder="artist or genre"
                    className="bg-bg-tertiary border-border text-text-primary placeholder:text-text-tertiary flex-1 rounded-lg border px-3 py-2 text-sm"
                  />
                  <button
                    onClick={addRedLine}
                    disabled={!newRedLine.trim()}
                    className="bg-bg-tertiary text-text-secondary hover:bg-bg-hover border-border rounded-lg border px-2.5 transition-colors disabled:opacity-50"
                    aria-label="Add"
                  >
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
                {form.redLines.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {form.redLines.map(line => (
                      <span
                        key={line}
                        className="bg-error/10 text-error flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium"
                      >
                        {line}
                        <button
                          onClick={() => removeRedLine(line)}
                          className="hover:text-error/80 p-0.5"
                          aria-label={`Remove ${line}`}
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>

              <button
                onClick={() => {
                  handleSave();
                }}
                disabled={isSaving}
                className="bg-accent text-text-on-accent hover:bg-accent-hover flex w-full items-center justify-center gap-2 rounded-lg py-2.5 text-sm font-semibold transition-colors disabled:opacity-50"
              >
                {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
                Save
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
