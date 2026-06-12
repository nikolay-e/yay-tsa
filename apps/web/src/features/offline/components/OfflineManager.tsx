import { useEffect, useState } from 'react';
import {
  Trash2,
  ShieldCheck,
  ShieldAlert,
  History,
  Loader2,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { useOfflineStore, useOfflineSettings } from '../stores/offline.store';

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 MB';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  return `${mb.toFixed(1)} MB`;
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
}: Readonly<{
  label: string;
  description: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}>) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0">
        <div className="text-sm font-medium">{label}</div>
        <div className="text-text-secondary text-xs">{description}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={cn(
          'focus-visible:ring-accent relative h-6 w-11 shrink-0 rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none',
          checked ? 'bg-accent' : 'bg-bg-tertiary'
        )}
      >
        <span
          className={cn(
            'absolute top-0.5 left-0.5 h-5 w-5 rounded-full bg-white transition-transform',
            checked && 'translate-x-5'
          )}
        />
      </button>
    </div>
  );
}

export function OfflineManager() {
  const entries = useOfflineStore(state => state.entries);
  const usageBytes = useOfflineStore(state => state.usageBytes);
  const persisted = useOfflineStore(state => state.persisted);
  const clearAll = useOfflineStore(state => state.clearAll);
  const clearListeningCache = useOfflineStore(state => state.clearListeningCache);
  const remove = useOfflineStore(state => state.remove);
  const refreshUsage = useOfflineStore(state => state.refreshUsage);
  const setSetting = useOfflineStore(state => state.setSetting);
  const settings = useOfflineSettings();
  const [confirmClearAll, setConfirmClearAll] = useState(false);
  const [isClearingAll, setIsClearingAll] = useState(false);
  const [showTrackList, setShowTrackList] = useState(false);

  useEffect(() => {
    refreshUsage().catch(() => {});
  }, [refreshUsage]);

  const downloaded = Object.entries(entries)
    .filter(([, entry]) => entry.status === 'ready')
    .sort(([, a], [, b]) => b.size - a.size);
  const cacheCount = downloaded.filter(
    ([, entry]) =>
      entry.reasons.includes('listening-cache') &&
      !entry.reasons.some(reason => reason !== 'listening-cache')
  ).length;

  return (
    <div className="p-4">
      <div className="mb-5 space-y-4">
        <ToggleRow
          label="Auto-download liked songs"
          description="Songs you like are saved for offline automatically."
          checked={settings.autoDownloadFavorites}
          onChange={value => setSetting({ autoDownloadFavorites: value })}
        />
        <ToggleRow
          label="Cache songs I play"
          description="Recently played songs are cached and rotated out by the limit below."
          checked={settings.autoCachePlayed}
          onChange={value => setSetting({ autoCachePlayed: value })}
        />
        <div className="flex items-center justify-between gap-3">
          <label htmlFor="max-cache-tracks" className="text-sm">
            Max cached played songs{' '}
            <span className="text-text-secondary text-xs">(0 = unlimited)</span>
          </label>
          <input
            id="max-cache-tracks"
            type="number"
            min={0}
            value={settings.maxCacheTracks}
            onChange={event =>
              setSetting({
                maxCacheTracks: Math.max(0, Math.floor(Number(event.target.value) || 0)),
              })
            }
            className="bg-bg-tertiary border-border text-text-primary w-24 rounded-lg border px-3 py-2 text-sm"
          />
        </div>
        <ToggleRow
          label="Remove downloads when unliked"
          description="Delete a song's download when you unlike it, unless an album or manual download still keeps it."
          checked={settings.removeUnlikedDownloads}
          onChange={value => setSetting({ removeUnlikedDownloads: value })}
        />
      </div>

      <div className="border-border my-4 border-t" />

      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <div className="font-medium">
            {downloaded.length} {downloaded.length === 1 ? 'track' : 'tracks'} downloaded
          </div>
          <div className="text-text-secondary text-sm">
            {formatBytes(usageBytes)} used{cacheCount > 0 ? ` · ${cacheCount} cached` : ''}
          </div>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          {cacheCount > 0 && (
            <button
              type="button"
              onClick={() => {
                clearListeningCache().catch(() => {});
              }}
              className="bg-bg-tertiary text-text-secondary hover:text-text-primary border-border flex items-center gap-2 rounded-md border px-3 py-2 text-sm transition-colors"
            >
              <History className="h-4 w-4" />
              Clear cache
            </button>
          )}
          {downloaded.length > 0 &&
            (confirmClearAll ? (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={isClearingAll}
                  onClick={() => {
                    setIsClearingAll(true);
                    clearAll()
                      .catch(() => {})
                      .finally(() => {
                        setIsClearingAll(false);
                        setConfirmClearAll(false);
                      });
                  }}
                  className="bg-error/10 hover:bg-error/20 text-error flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors disabled:opacity-50"
                >
                  {isClearingAll ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Trash2 className="h-4 w-4" />
                  )}
                  Delete {downloaded.length} {downloaded.length === 1 ? 'download' : 'downloads'}
                </button>
                <button
                  type="button"
                  disabled={isClearingAll}
                  onClick={() => setConfirmClearAll(false)}
                  className="text-text-secondary hover:text-text-primary rounded-md px-2 py-2 text-sm transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmClearAll(true)}
                className="bg-error/10 hover:bg-error/20 text-error flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors"
              >
                <Trash2 className="h-4 w-4" />
                Clear all
              </button>
            ))}
        </div>
      </div>

      <div className="text-text-secondary mb-4 flex items-center gap-2 text-xs">
        {persisted ? (
          <>
            <ShieldCheck className="h-4 w-4 text-green-500" />
            Persistent storage granted — downloads survive storage pressure
          </>
        ) : (
          <>
            <ShieldAlert className="h-4 w-4" />
            Best-effort storage — the browser may evict downloads under pressure
          </>
        )}
      </div>

      {downloaded.length === 0 ? (
        <p className="text-text-secondary text-sm">
          No downloads yet. Like a song or play one and it will be saved here automatically — or tap
          the download icon on any track.
        </p>
      ) : (
        <div>
          <button
            type="button"
            onClick={() => setShowTrackList(value => !value)}
            aria-expanded={showTrackList}
            className="text-text-secondary hover:text-text-primary flex w-full items-center justify-between py-2 text-sm font-medium transition-colors"
          >
            <span>
              {showTrackList ? 'Hide' : 'Show'} downloaded tracks ({downloaded.length})
            </span>
            {showTrackList ? (
              <ChevronUp className="h-4 w-4" />
            ) : (
              <ChevronDown className="h-4 w-4" />
            )}
          </button>
          {showTrackList && (
            <ul className="divide-border divide-y">
              {downloaded.map(([trackId, entry]) => (
                <li key={trackId} className="flex items-center justify-between gap-3 py-2">
                  <span className="min-w-0 flex-1 truncate text-sm">{entry.name}</span>
                  <span className="text-text-tertiary shrink-0 text-xs">
                    {formatBytes(entry.size)}
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      remove(trackId).catch(() => {});
                    }}
                    className="text-text-secondary hover:text-error shrink-0 rounded-full p-1.5 transition-colors"
                    aria-label={`Remove ${entry.name}`}
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
