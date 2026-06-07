import { useEffect } from 'react';
import { Trash2, ShieldCheck, ShieldAlert } from 'lucide-react';
import { useOfflineStore } from '../stores/offline.store';

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 MB';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  return `${mb.toFixed(1)} MB`;
}

export function OfflineManager() {
  const entries = useOfflineStore(state => state.entries);
  const usageBytes = useOfflineStore(state => state.usageBytes);
  const persisted = useOfflineStore(state => state.persisted);
  const clearAll = useOfflineStore(state => state.clearAll);
  const remove = useOfflineStore(state => state.remove);
  const refreshUsage = useOfflineStore(state => state.refreshUsage);

  useEffect(() => {
    refreshUsage().catch(() => {});
  }, [refreshUsage]);

  const downloaded = Object.entries(entries).filter(([, entry]) => entry.status === 'ready');

  return (
    <div className="p-4">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <div className="font-medium">
            {downloaded.length} {downloaded.length === 1 ? 'track' : 'tracks'} downloaded
          </div>
          <div className="text-text-secondary text-sm">{formatBytes(usageBytes)} used</div>
        </div>
        {downloaded.length > 0 && (
          <button
            type="button"
            onClick={() => {
              clearAll().catch(() => {});
            }}
            className="bg-error/10 hover:bg-error/20 text-error flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors"
          >
            <Trash2 className="h-4 w-4" />
            Clear all
          </button>
        )}
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
          No downloads yet. Tap the download icon on any track to make it available offline.
        </p>
      ) : (
        <ul className="divide-border divide-y">
          {downloaded.map(([trackId, entry]) => (
            <li key={trackId} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0 flex-1 truncate text-sm">{entry.name}</span>
              <span className="text-text-tertiary shrink-0 text-xs">{formatBytes(entry.size)}</span>
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
  );
}
