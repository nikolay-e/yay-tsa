import { useState, useEffect } from 'react';
import { Trash2, RefreshCw, HardDrive, Database, Info, Server } from 'lucide-react';
import { APP_VERSION, AdminService, type CacheStats } from '@yaytsa/core';
import { queryClient } from '@/shared/lib/query-client';
import { useAuthStore } from '@/features/auth/stores/auth.store';

async function clearServiceWorkerCaches(): Promise<number> {
  if (!('caches' in window)) return 0;
  const cacheNames = await caches.keys();
  const yaytsCaches = cacheNames.filter(name => name.startsWith('yaytsa-'));
  await Promise.all(yaytsCaches.map(name => caches.delete(name)));
  return yaytsCaches.length;
}

async function clearAllBrowserCaches(): Promise<{ sw: number }> {
  const swCount = await clearServiceWorkerCaches();
  queryClient.clear();
  return { sw: swCount };
}

async function forceReload(): Promise<void> {
  await clearAllBrowserCaches();
  if ('serviceWorker' in navigator) {
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map(reg => reg.unregister()));
  }
  window.location.reload();
}

export function SettingsPage() {
  const client = useAuthStore(state => state.client);
  const [status, setStatus] = useState<string | null>(null);
  const [isClearing, setIsClearing] = useState(false);
  const [isReloading, setIsReloading] = useState(false);
  const [isClearingServer, setIsClearingServer] = useState(false);
  const [serverStats, setServerStats] = useState<CacheStats | null>(null);

  useEffect(() => {
    if (!client) {
      setServerStats(null);
      return;
    }
    let cancelled = false;
    const adminService = new AdminService(client);
    adminService
      .getCacheStats()
      .then(stats => {
        if (!cancelled) setServerStats(stats);
      })
      .catch(() => {
        if (!cancelled) setServerStats(null);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  const handleClearBrowserCaches = async () => {
    setIsClearing(true);
    setStatus(null);
    try {
      const { sw } = await clearAllBrowserCaches();
      setStatus(`Cleared ${sw} service worker cache(s) and React Query cache`);
    } catch (error) {
      setStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsClearing(false);
    }
  };

  const handleClearServerCaches = async () => {
    if (!client) return;
    const adminService = new AdminService(client);
    setIsClearingServer(true);
    setStatus(null);
    try {
      const result = await adminService.clearAllCaches();
      setStatus(`Server: ${result.message} (${result.entriesCleared} entries cleared)`);
      const stats = await adminService.getCacheStats();
      setServerStats(stats);
    } catch (error) {
      setStatus(`Server error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsClearingServer(false);
    }
  };

  const handleClearAllCaches = async () => {
    setIsClearing(true);
    setIsClearingServer(true);
    setStatus(null);
    try {
      const { sw } = await clearAllBrowserCaches();
      let serverMsg = '';
      if (client) {
        const adminService = new AdminService(client);
        const result = await adminService.clearAllCaches();
        serverMsg = `, server: ${result.entriesCleared} entries`;
        const stats = await adminService.getCacheStats();
        setServerStats(stats);
      }
      setStatus(`Cleared browser (${sw} SW caches, React Query)${serverMsg}`);
    } catch (error) {
      setStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsClearing(false);
      setIsClearingServer(false);
    }
  };

  const handleForceReload = async () => {
    setIsReloading(true);
    setStatus('Clearing caches and reloading...');
    if (client) {
      try {
        const adminService = new AdminService(client);
        await adminService.clearAllCaches();
      } catch {
        // Continue with reload even if server clear fails
      }
    }
    await forceReload();
  };

  const handleClearLocalStorage = () => {
    const keys = Object.keys(localStorage).filter(k => k.startsWith('yaytsa_'));
    keys.forEach(k => localStorage.removeItem(k));
    setStatus(`Cleared ${keys.length} localStorage item(s)`);
  };

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="mb-6 text-2xl font-bold">Settings</h1>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Server className="h-4 w-4" />
          Server Cache
        </h2>

        {serverStats && (
          <div className="bg-bg-secondary border-border mb-3 rounded-lg border p-4">
            <div className="grid grid-cols-2 gap-2 text-sm">
              <span className="text-text-secondary">Cached images:</span>
              <span className="font-mono">{serverStats.imageCache.size}</span>
              <span className="text-text-secondary">Hit rate:</span>
              <span className="font-mono">
                {(serverStats.imageCache.hitRate * 100).toFixed(1)}%
              </span>
              <span className="text-text-secondary">Hits / Misses:</span>
              <span className="font-mono">
                {serverStats.imageCache.hitCount} / {serverStats.imageCache.missCount}
              </span>
            </div>
          </div>
        )}

        <button
          onClick={() => void handleClearServerCaches()}
          disabled={isClearingServer || !client}
          className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
        >
          <Trash2 className="text-accent h-5 w-5 shrink-0" />
          <div>
            <div className="font-medium">Clear Server Cache</div>
            <div className="text-text-secondary text-sm">
              Clear image cache on backend. Forces reload from disk.
            </div>
          </div>
        </button>
      </section>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <HardDrive className="h-4 w-4" />
          Browser Cache
        </h2>

        <div className="space-y-3">
          <button
            onClick={() => void handleClearBrowserCaches()}
            disabled={isClearing}
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
          >
            <Trash2 className="text-accent h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Clear Browser Caches</div>
              <div className="text-text-secondary text-sm">
                Clear service worker and React Query caches
              </div>
            </div>
          </button>

          <button
            onClick={handleClearLocalStorage}
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors"
          >
            <Database className="text-accent h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Clear Local Storage</div>
              <div className="text-text-secondary text-sm">
                Clear saved preferences (volume, device ID)
              </div>
            </div>
          </button>
        </div>
      </section>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <RefreshCw className="h-4 w-4" />
          Full Reset
        </h2>

        <div className="space-y-3">
          <button
            onClick={() => void handleClearAllCaches()}
            disabled={isClearing || isClearingServer}
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
          >
            <Trash2 className="text-accent h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Clear All Caches</div>
              <div className="text-text-secondary text-sm">
                Clear both server and browser caches
              </div>
            </div>
          </button>

          <button
            onClick={() => void handleForceReload()}
            disabled={isReloading}
            className="bg-error/10 hover:bg-error/20 border-error/30 flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
          >
            <RefreshCw
              className={`text-error h-5 w-5 shrink-0 ${isReloading ? 'animate-spin' : ''}`}
            />
            <div>
              <div className="text-error font-medium">Force Reload</div>
              <div className="text-text-secondary text-sm">
                Clear all caches, unregister service worker, and reload page
              </div>
            </div>
          </button>
        </div>
      </section>

      {status && <div className="bg-bg-tertiary mb-8 rounded-lg p-3 text-sm">{status}</div>}

      <section>
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Info className="h-4 w-4" />
          About
        </h2>

        <div className="bg-bg-secondary border-border rounded-lg border p-4">
          <div className="flex justify-between">
            <span className="text-text-secondary">Version</span>
            <span className="font-mono">{APP_VERSION}</span>
          </div>
        </div>
      </section>
    </div>
  );
}
