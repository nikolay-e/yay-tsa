import { useEffect, useState, useCallback, useRef } from 'react';
import { Plus, X, Loader2, ChevronDown, ChevronUp, Search, ListPlus } from 'lucide-react';
import {
  AdaptiveDjService,
  ItemsService,
  type UserPreferences,
  type RecommendedTrack,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { toast } from '@/shared/ui/Toast';

interface FormState {
  maxArtistConsecutive: number;
  noRepeatHours: number;
  discoveryPct: number;
  redLines: string[];
}

const DEFAULT_FORM: FormState = {
  maxArtistConsecutive: 2,
  noRepeatHours: 4,
  discoveryPct: 30,
  redLines: [],
};

function prefsToForm(prefs: UserPreferences): FormState {
  return {
    maxArtistConsecutive:
      (prefs.hardRules['maxArtistConsecutive'] as number) ?? DEFAULT_FORM.maxArtistConsecutive,
    noRepeatHours: (prefs.hardRules['noRepeatHours'] as number) ?? DEFAULT_FORM.noRepeatHours,
    discoveryPct: (prefs.djStyle['discoveryPct'] as number) ?? DEFAULT_FORM.discoveryPct,
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
    djStyle: { discoveryPct: form.discoveryPct },
    redLines: form.redLines,
  };
}

function discoveryLabel(pct: number): string {
  if (pct <= 10) return 'Conservative';
  if (pct <= 30) return 'Mostly familiar';
  if (pct <= 60) return 'Balanced';
  if (pct <= 85) return 'Adventurous';
  return 'Full discovery';
}

export function DjPreferencesPanel() {
  const client = useAuthStore(state => state.client);
  const userId = useAuthStore(state => state.userId);
  const [form, setForm] = useState<FormState>(DEFAULT_FORM);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [newRedLine, setNewRedLine] = useState('');
  const [isExpanded, setExpanded] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<RecommendedTrack[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const searchTimeout = useRef<ReturnType<typeof setTimeout>>(null);
  const mountedRef = useRef(true);

  const handleEnqueue = useCallback(
    async (trackId: string) => {
      if (!client) return;
      try {
        const itemsService = new ItemsService(client);
        const items = await itemsService.getItemsByIds([trackId]);
        if (items.length > 0) {
          usePlayerStore.getState().appendToQueue(items);
          toast.add('success', `Added "${items[0]?.Name}" to queue`);
        }
      } catch {
        toast.add('error', 'Failed to add track to queue');
      }
    },
    [client]
  );

  const handleSearch = useCallback(
    (query: string) => {
      setSearchQuery(query);
      if (searchTimeout.current) clearTimeout(searchTimeout.current);
      if (!query.trim() || !client) {
        setSearchResults([]);
        return;
      }
      searchTimeout.current = setTimeout(async () => {
        setIsSearching(true);
        try {
          const service = new AdaptiveDjService(client);
          const results = await service.searchByText(query.trim(), 10);
          if (mountedRef.current) setSearchResults(results);
        } catch {
          if (mountedRef.current) setSearchResults([]);
        } finally {
          if (mountedRef.current) setIsSearching(false);
        }
      }, 400);
    },
    [client]
  );

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      if (searchTimeout.current) clearTimeout(searchTimeout.current);
    };
  }, []);

  useEffect(() => {
    if (!client || !userId) return;

    const service = new AdaptiveDjService(client);
    service
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
                <label
                  htmlFor="discovery-slider"
                  className="text-text-secondary mb-1 block text-xs font-medium tracking-wide uppercase"
                >
                  New music — {form.discoveryPct}%
                </label>
                <input
                  id="discovery-slider"
                  type="range"
                  min={0}
                  max={100}
                  step={5}
                  value={form.discoveryPct}
                  onChange={e =>
                    setForm(prev => ({ ...prev, discoveryPct: Number(e.target.value) }))
                  }
                  className="accent-accent w-full"
                />
                <div className="text-text-tertiary mt-0.5 flex justify-between text-[10px]">
                  <span>Familiar</span>
                  <span className="text-text-secondary text-xs">
                    {discoveryLabel(form.discoveryPct)}
                  </span>
                  <span>Discovery</span>
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
                    No-repeat (listening hours)
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

              <div>
                <label
                  htmlFor="semantic-search-input"
                  className="text-text-secondary mb-1 block text-xs"
                >
                  Find similar music
                </label>
                <div className="relative">
                  <Search className="text-text-tertiary absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
                  <input
                    id="semantic-search-input"
                    type="text"
                    value={searchQuery}
                    onChange={e => handleSearch(e.target.value)}
                    placeholder="upbeat jazz, dark ambient, 90s rock..."
                    className="bg-bg-tertiary border-border text-text-primary placeholder:text-text-tertiary w-full rounded-lg border py-2 pr-3 pl-9 text-sm"
                  />
                  {isSearching && (
                    <Loader2 className="text-accent absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2 animate-spin" />
                  )}
                </div>
                {searchResults.length > 0 && (
                  <div className="bg-bg-tertiary border-border mt-1.5 max-h-48 overflow-y-auto rounded-lg border">
                    {searchResults.map(track => (
                      <div
                        key={track.trackId}
                        className="border-border flex items-center justify-between border-b px-3 py-2 last:border-0"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="text-text-primary truncate text-sm">{track.name}</div>
                          <div className="text-text-secondary truncate text-xs">
                            {track.artistName}
                          </div>
                        </div>
                        <button
                          onClick={() => handleEnqueue(track.trackId)}
                          className="text-text-secondary hover:text-accent ml-2 shrink-0 p-1 transition-colors"
                          aria-label={`Add ${track.name} to queue`}
                        >
                          <ListPlus className="h-4 w-4" />
                        </button>
                      </div>
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
