import { useState, useEffect } from 'react';
import { RefreshCw, HardDrive, Info, Server, LogOut, Upload, Key, Link } from 'lucide-react';
import { AdminService, MediaServerError } from '@yay-tsa/core';
import { queryClient } from '@/shared/lib/query-client';
import { useAuthStore, useIsAdmin } from '@/features/auth/stores/auth.store';
import { TrackUploadDialog } from '@/features/library/components';
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

interface MetadataKeys {
  geniusToken: string;
  lastfmKey: string;
  spotifyClientId: string;
  spotifyClientSecret: string;
}

interface MetadataConfigured {
  geniusToken: boolean;
  lastfmKey: boolean;
  spotifyClientId: boolean;
  spotifyClientSecret: boolean;
}

interface ServiceUrls {
  separatorUrl: string;
  lyricsFetcherUrl: string;
}

export function SettingsPage() {
  const client = useAuthStore(state => state.client);
  const logout = useAuthStore(state => state.logout);
  const isAdmin = useIsAdmin();
  const [status, setStatus] = useState<string | null>(null);
  const [isRescanning, setIsRescanning] = useState(false);
  const [isReloading, setIsReloading] = useState(false);
  const [isUploadOpen, setIsUploadOpen] = useState(false);
  const [metadataKeys, setMetadataKeys] = useState<MetadataKeys>({
    geniusToken: '',
    lastfmKey: '',
    spotifyClientId: '',
    spotifyClientSecret: '',
  });
  const [metadataConfigured, setMetadataConfigured] = useState<MetadataConfigured>({
    geniusToken: false,
    lastfmKey: false,
    spotifyClientId: false,
    spotifyClientSecret: false,
  });
  const [metadataSaveStatus, setMetadataSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [serviceUrls, setServiceUrls] = useState<ServiceUrls>({
    separatorUrl: '',
    lyricsFetcherUrl: '',
  });
  const [serviceSaveStatus, setServiceSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');

  useEffect(() => {
    if (!client || !isAdmin) return;
    fetch('/api/Admin/Settings/metadata', {
      headers: { 'X-Emby-Authorization': client.buildAuthHeader() },
    })
      .then(r => r.ok ? r.json() as Promise<Record<string, string>> : Promise.reject(r))
      .then(data => {
        // Track which keys are configured (non-empty) but do NOT pre-fill
        // input fields with masked values — doing so would corrupt the stored
        // secret when the user clicks Save without making changes.
        setMetadataConfigured({
          geniusToken: !!(data['metadata.genius.token']),
          lastfmKey: !!(data['metadata.lastfm.api-key']),
          spotifyClientId: !!(data['metadata.spotify.client-id']),
          spotifyClientSecret: !!(data['metadata.spotify.client-secret']),
        });
      })
      .catch(() => { /* ignore load errors */ });

    fetch('/api/Admin/Settings/services', {
      headers: { 'X-Emby-Authorization': client.buildAuthHeader() },
    })
      .then(r => r.ok ? r.json() as Promise<Record<string, string>> : Promise.reject(r))
      .then(data => {
        setServiceUrls({
          separatorUrl: data['service.separator-url'] ?? '',
          lyricsFetcherUrl: data['service.lyrics-url'] ?? '',
        });
      })
      .catch(() => { /* ignore load errors */ });
  }, [client, isAdmin]);

  const handleSaveMetadataKeys = async () => {
    if (!client) return;
    setMetadataSaveStatus('saving');
    try {
      // Only send keys that the user actually filled in — empty fields mean
      // "keep existing value" and must not overwrite what is stored.
      const payload: Record<string, string> = {};
      if (metadataKeys.geniusToken) payload['metadata.genius.token'] = metadataKeys.geniusToken;
      if (metadataKeys.lastfmKey) payload['metadata.lastfm.api-key'] = metadataKeys.lastfmKey;
      if (metadataKeys.spotifyClientId) payload['metadata.spotify.client-id'] = metadataKeys.spotifyClientId;
      if (metadataKeys.spotifyClientSecret) payload['metadata.spotify.client-secret'] = metadataKeys.spotifyClientSecret;

      const res = await fetch('/api/Admin/Settings/metadata', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-Emby-Authorization': client.buildAuthHeader(),
        },
        body: JSON.stringify(payload),
      });
      if (res.ok) {
        setMetadataConfigured(prev => ({
          geniusToken: prev.geniusToken || !!metadataKeys.geniusToken,
          lastfmKey: prev.lastfmKey || !!metadataKeys.lastfmKey,
          spotifyClientId: prev.spotifyClientId || !!metadataKeys.spotifyClientId,
          spotifyClientSecret: prev.spotifyClientSecret || !!metadataKeys.spotifyClientSecret,
        }));
        setMetadataKeys({ geniusToken: '', lastfmKey: '', spotifyClientId: '', spotifyClientSecret: '' });
        setMetadataSaveStatus('saved');
        setTimeout(() => setMetadataSaveStatus('idle'), 2000);
      } else {
        setMetadataSaveStatus('error');
      }
    } catch {
      setMetadataSaveStatus('error');
    }
  };

  const handleSaveServiceUrls = async () => {
    if (!client) return;
    setServiceSaveStatus('saving');
    try {
      const res = await fetch('/api/Admin/Settings/services', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-Emby-Authorization': client.buildAuthHeader(),
        },
        body: JSON.stringify({
          'service.separator-url': serviceUrls.separatorUrl,
          'service.lyrics-url': serviceUrls.lyricsFetcherUrl,
        }),
      });
      if (res.ok) {
        setServiceSaveStatus('saved');
        setTimeout(() => setServiceSaveStatus('idle'), 2000);
      } else {
        setServiceSaveStatus('error');
      }
    } catch {
      setServiceSaveStatus('error');
    }
  };

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

  const handleUploadSuccess = () => {
    setStatus('Album uploaded successfully. Rescanning library...');
    void queryClient.invalidateQueries({ queryKey: ['tracks'] });
    void queryClient.invalidateQueries({ queryKey: ['albums'] });
    void queryClient.invalidateQueries({ queryKey: ['artists'] });
    void queryClient.invalidateQueries({ queryKey: ['album'] });
    void queryClient.invalidateQueries({ queryKey: ['artist'] });
    void handleRescanLibrary();
  };

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="mb-6 text-2xl font-bold">Settings</h1>

      {isAdmin && (
        <section className="mb-8">
          <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
            <Upload className="h-4 w-4" />
            Upload
          </h2>

          <button
            onClick={() => setIsUploadOpen(true)}
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors"
          >
            <Upload className="text-accent h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Upload Album</div>
              <div className="text-text-secondary text-sm">
                Upload audio files to the library. Select all tracks of an album at once.
              </div>
            </div>
          </button>
        </section>
      )}

      {isAdmin && (
        <section className="mb-8">
          <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
            <Key className="h-4 w-4" />
            Metadata Providers
          </h2>

          <div className="bg-bg-secondary border-border space-y-4 rounded-lg border p-4">
            <p className="text-text-secondary text-sm">
              MusicBrainz and iTunes are always enabled (no key required). Configure optional providers below.
            </p>

            <div className="space-y-3">
              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Genius Access Token
                  {metadataConfigured.geniusToken && !metadataKeys.geniusToken && (
                    <span className="text-success ml-2 normal-case">✓ Configured</span>
                  )}
                </label>
                <input
                  type="password"
                  value={metadataKeys.geniusToken}
                  onChange={e => setMetadataKeys(k => ({ ...k, geniusToken: e.target.value }))}
                  placeholder={metadataConfigured.geniusToken ? 'Leave blank to keep existing' : 'Enter Genius access token'}
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>

              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Last.fm API Key
                  {metadataConfigured.lastfmKey && !metadataKeys.lastfmKey && (
                    <span className="text-success ml-2 normal-case">✓ Configured</span>
                  )}
                </label>
                <input
                  type="password"
                  value={metadataKeys.lastfmKey}
                  onChange={e => setMetadataKeys(k => ({ ...k, lastfmKey: e.target.value }))}
                  placeholder={metadataConfigured.lastfmKey ? 'Leave blank to keep existing' : 'Enter Last.fm API key'}
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>

              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Spotify Client ID
                  {metadataConfigured.spotifyClientId && !metadataKeys.spotifyClientId && (
                    <span className="text-success ml-2 normal-case">✓ Configured</span>
                  )}
                </label>
                <input
                  type="password"
                  value={metadataKeys.spotifyClientId}
                  onChange={e => setMetadataKeys(k => ({ ...k, spotifyClientId: e.target.value }))}
                  placeholder={metadataConfigured.spotifyClientId ? 'Leave blank to keep existing' : 'Enter Spotify client ID'}
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>

              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Spotify Client Secret
                  {metadataConfigured.spotifyClientSecret && !metadataKeys.spotifyClientSecret && (
                    <span className="text-success ml-2 normal-case">✓ Configured</span>
                  )}
                </label>
                <input
                  type="password"
                  value={metadataKeys.spotifyClientSecret}
                  onChange={e => setMetadataKeys(k => ({ ...k, spotifyClientSecret: e.target.value }))}
                  placeholder={metadataConfigured.spotifyClientSecret ? 'Leave blank to keep existing' : 'Enter Spotify client secret'}
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>
            </div>

            <button
              onClick={() => void handleSaveMetadataKeys()}
              disabled={metadataSaveStatus === 'saving'}
              className="bg-accent hover:bg-accent-hover rounded px-4 py-2 text-sm font-medium text-black transition-colors disabled:opacity-50"
            >
              {metadataSaveStatus === 'saving' && 'Saving...'}
              {metadataSaveStatus === 'saved' && 'Saved ✓'}
              {metadataSaveStatus === 'error' && 'Error ✗'}
              {metadataSaveStatus === 'idle' && 'Save'}
            </button>
          </div>
        </section>
      )}

      {isAdmin && (
        <section className="mb-8">
          <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
            <Link className="h-4 w-4" />
            Services
          </h2>

          <div className="bg-bg-secondary border-border space-y-4 rounded-lg border p-4">
            <p className="text-text-secondary text-sm">
              URLs for optional sidecar services. Leave blank to use the default Docker service address.
            </p>

            <div className="space-y-3">
              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Audio Separator URL
                </label>
                <input
                  type="text"
                  value={serviceUrls.separatorUrl}
                  onChange={e => setServiceUrls(u => ({ ...u, separatorUrl: e.target.value }))}
                  placeholder="http://audio-separator:8000"
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>

              <div>
                <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                  Lyrics Fetcher URL
                </label>
                <input
                  type="text"
                  value={serviceUrls.lyricsFetcherUrl}
                  onChange={e => setServiceUrls(u => ({ ...u, lyricsFetcherUrl: e.target.value }))}
                  placeholder="http://audio-separator:8000/api/fetch-lyrics"
                  className="bg-bg-tertiary border-border text-text-primary w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-white/30"
                />
              </div>
            </div>

            <button
              onClick={() => void handleSaveServiceUrls()}
              disabled={serviceSaveStatus === 'saving'}
              className="bg-accent hover:bg-accent-hover rounded px-4 py-2 text-sm font-medium text-black transition-colors disabled:opacity-50"
            >
              {serviceSaveStatus === 'saving' && 'Saving...'}
              {serviceSaveStatus === 'saved' && 'Saved ✓'}
              {serviceSaveStatus === 'error' && 'Error ✗'}
              {serviceSaveStatus === 'idle' && 'Save'}
            </button>
          </div>
        </section>
      )}

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
            <div className="font-medium">Force Reload</div>
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

      <TrackUploadDialog
        isOpen={isUploadOpen}
        onClose={() => setIsUploadOpen(false)}
        onUploadSuccess={handleUploadSuccess}
      />
    </div>
  );
}
