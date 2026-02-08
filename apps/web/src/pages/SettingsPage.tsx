import { useState } from 'react';
import { RefreshCw, HardDrive, Info, Server, LogOut } from 'lucide-react';
import { AdminService, MediaServerError } from '@yay-tsa/core';
import { queryClient } from '@/shared/lib/query-client';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { VersionInfo } from '@/shared/components/VersionInfo';

async function clearServiceWorkerCaches(): Promise<number> {
  if (!('caches' in window)) return 0;
  const cacheNames = await caches.keys();
  const yaytsCaches = cacheNames.filter(name => name.startsWith('yaytsa-'));
  await Promise.all(yaytsCaches.map(name => caches.delete(name)));
  return yaytsCaches.length;
}

async function clearAllBrowserCaches(): Promise<void> {
  await clearServiceWorkerCaches();
  queryClient.clear();
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
  const logout = useAuthStore(state => state.logout);
  const [status, setStatus] = useState<string | null>(null);
  const [isRescanning, setIsRescanning] = useState(false);
  const [isReloading, setIsReloading] = useState(false);

  const handleRescanLibrary = async () => {
    if (!client) return;
    const adminService = new AdminService(client);
    setIsRescanning(true);
    setStatus(null);
    try {
      const result = await adminService.rescanLibrary();
      setStatus(result.message);
    } catch (error) {
      if (error instanceof MediaServerError && error.statusCode === 409) {
        setStatus('Scan already in progress');
      } else {
        setStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
      }
    } finally {
      setIsRescanning(false);
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

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="mb-6 text-2xl font-bold">Settings</h1>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Server className="h-4 w-4" />
          Library
        </h2>

        <button
          onClick={() => void handleRescanLibrary()}
          disabled={isRescanning || !client}
          className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
        >
          <HardDrive
            className={`text-accent h-5 w-5 shrink-0 ${isRescanning ? 'animate-pulse' : ''}`}
          />
          <div>
            <div className="font-medium">Reload Media</div>
            <div className="text-text-secondary text-sm">
              Rescan library from disk. Discovers new files and removes deleted ones.
            </div>
          </div>
        </button>
      </section>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <RefreshCw className="h-4 w-4" />
          Reset
        </h2>

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
              Clear all caches and reload the application
            </div>
          </div>
        </button>
      </section>

      {status && <div className="bg-bg-tertiary mb-8 rounded-lg p-3 text-sm">{status}</div>}

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Info className="h-4 w-4" />
          About
        </h2>

        <div className="bg-bg-secondary border-border rounded-lg border p-4">
          <VersionInfo />
        </div>
      </section>

      <section>
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <LogOut className="h-4 w-4" />
          Account
        </h2>

        <button
          onClick={() => void logout()}
          className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors"
        >
          <LogOut className="text-error h-5 w-5 shrink-0" />
          <div>
            <div className="font-medium">Logout</div>
            <div className="text-text-secondary text-sm">Sign out of your account</div>
          </div>
        </button>
      </section>
    </div>
  );
}
