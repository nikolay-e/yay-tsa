import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  RefreshCw,
  Info,
  LogOut,
  Sparkles,
  Upload,
  Users,
  Smartphone,
  Download,
  KeyRound,
} from 'lucide-react';
import { AdminService, MediaServerError } from '@yay-tsa/core';
import { queryClient } from '@/shared/lib/query-client';
import { useAuthStore, useIsAdmin } from '@/features/auth/stores/auth.store';
import { TrackUploadDialog } from '@/features/library/components';
import { VersionInfo } from '@/shared/components/VersionInfo';
import { DjPreferencesPanel } from '@/features/player/components/DjPreferencesPanel';
import { UsersPanel } from '@/features/auth/components/UsersPanel';
import { OfflineManager } from '@/features/offline';

function isStandaloneMode(): boolean {
  return (
    globalThis.matchMedia?.('(display-mode: standalone)').matches ||
    (globalThis.navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

async function clearServiceWorkerCaches(): Promise<number> {
  if (!('caches' in globalThis)) return 0;
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
  globalThis.location.reload();
}

export function SettingsPage() {
  const client = useAuthStore(state => state.client);
  const logout = useAuthStore(state => state.logout);
  const changePassword = useAuthStore(state => state.changePassword);
  const isAdmin = useIsAdmin();
  const [status, setStatus] = useState<string | null>(null);
  const [isReloading, setIsReloading] = useState(false);
  const [confirmReload, setConfirmReload] = useState(false);
  const [confirmLogout, setConfirmLogout] = useState(false);
  const [isUploadOpen, setIsUploadOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isChangingPassword, setIsChangingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSuccess, setPasswordSuccess] = useState(false);

  const triggerRescanAfterUpload = async () => {
    if (!client) return;
    try {
      await new AdminService(client).rescanLibrary();
    } catch {
      // Upload already succeeded; a failed rescan trigger is non-fatal because
      // background refresh will pick up new files on the next library refetch.
    }
  };

  const handleChangePassword = async (event: FormEvent) => {
    event.preventDefault();
    setPasswordError(null);
    setPasswordSuccess(false);

    if (newPassword.length < 8) {
      setPasswordError('New password must be at least 8 characters');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('New passwords do not match');
      return;
    }

    setIsChangingPassword(true);
    try {
      await changePassword(currentPassword, newPassword);
      setPasswordSuccess(true);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (error) {
      if (
        error instanceof MediaServerError &&
        (error.statusCode === 401 || error.statusCode === 403)
      ) {
        setPasswordError('Current password is incorrect');
      } else {
        setPasswordError(error instanceof Error ? error.message : 'Failed to change password');
      }
    } finally {
      setIsChangingPassword(false);
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
    queryClient.invalidateQueries({ queryKey: ['tracks'] });
    queryClient.invalidateQueries({ queryKey: ['albums'] });
    queryClient.invalidateQueries({ queryKey: ['artists'] });
    queryClient.invalidateQueries({ queryKey: ['album'] });
    queryClient.invalidateQueries({ queryKey: ['artist'] });
    triggerRescanAfterUpload();
  };

  return (
    <div className="mx-auto max-w-2xl p-6">
      <h1 className="mb-6 text-2xl font-bold">Settings</h1>

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

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <RefreshCw className="h-4 w-4" />
          Reset
        </h2>

        {confirmReload ? (
          <div className="bg-error/10 border-error/30 rounded-lg border p-4">
            <div className="font-medium">Clear all caches and reload?</div>
            <div className="text-text-secondary mb-3 text-sm">
              This removes offline downloads and cached audio. You will need to download tracks
              again for offline playback.
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => {
                  handleForceReload();
                }}
                disabled={isReloading}
                className="bg-error/20 text-error hover:bg-error/30 flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`h-4 w-4 ${isReloading ? 'animate-spin' : ''}`} />
                Clear and reload
              </button>
              <button
                onClick={() => setConfirmReload(false)}
                disabled={isReloading}
                className="bg-bg-tertiary text-text-primary hover:bg-bg-hover rounded-md px-4 py-2 text-sm transition-colors disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <button
            onClick={() => setConfirmReload(true)}
            className="bg-error/10 hover:bg-error/20 border-error/30 flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors disabled:opacity-50"
          >
            <RefreshCw className="text-error h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Force Reload</div>
              <div className="text-text-secondary text-sm">
                Clear all caches and reload the application
              </div>
            </div>
          </button>
        )}
      </section>

      {status && <div className="bg-bg-tertiary mb-8 rounded-lg p-3 text-sm">{status}</div>}

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center justify-between gap-2 text-sm font-medium tracking-wide uppercase">
          <span className="flex items-center gap-2">
            <Download className="h-4 w-4" />
            Offline Downloads
          </span>
          <Link
            to="/offline"
            className="text-accent min-h-11 text-xs font-medium normal-case underline-offset-4 hover:underline focus-visible:underline"
          >
            View downloaded library
          </Link>
        </h2>
        <div className="bg-bg-secondary border-border rounded-lg border">
          <OfflineManager />
        </div>
      </section>

      {isAdmin && (
        <section className="mb-8">
          <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
            <Users className="h-4 w-4" />
            Users
          </h2>
          <div className="bg-bg-secondary border-border rounded-lg border">
            <UsersPanel />
          </div>
        </section>
      )}

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Sparkles className="h-4 w-4" />
          DJ Preferences
        </h2>
        <p className="text-text-secondary mb-3 text-sm">
          Energy ramp, genre weighting, and similarity thresholds for the auto-DJ queue.
        </p>
        <div className="bg-bg-secondary border-border rounded-lg border">
          <DjPreferencesPanel />
        </div>
      </section>

      {!isStandaloneMode() && (
        <section className="mb-8">
          <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
            <Smartphone className="h-4 w-4" />
            Android App
          </h2>

          <a
            href="/downloads/yay-tsa.apk"
            download
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors"
          >
            <Smartphone className="text-accent h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Download Android App</div>
              <div className="text-text-secondary text-sm">
                Install the native Android wrapper for a full-screen experience
              </div>
            </div>
          </a>
        </section>
      )}

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <Info className="h-4 w-4" />
          About
        </h2>

        <div className="bg-bg-secondary border-border rounded-lg border p-4">
          <VersionInfo />
        </div>
      </section>

      <section className="mb-8">
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <KeyRound className="h-4 w-4" />
          Change Password
        </h2>

        <form
          onSubmit={handleChangePassword}
          className="bg-bg-secondary border-border space-y-4 rounded-lg border p-4"
        >
          <div>
            <label
              htmlFor="current-password"
              className="text-text-secondary mb-1 block text-sm font-medium"
            >
              Current password
            </label>
            <input
              id="current-password"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={e => setCurrentPassword(e.target.value)}
              required
              className="bg-bg-tertiary border-border text-text-primary focus:border-accent min-h-11 w-full rounded-md border px-3 py-2 text-sm focus:outline-none"
            />
          </div>
          <div>
            <label
              htmlFor="new-password"
              className="text-text-secondary mb-1 block text-sm font-medium"
            >
              New password
            </label>
            <input
              id="new-password"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              required
              minLength={8}
              className="bg-bg-tertiary border-border text-text-primary focus:border-accent min-h-11 w-full rounded-md border px-3 py-2 text-sm focus:outline-none"
            />
          </div>
          <div>
            <label
              htmlFor="confirm-password"
              className="text-text-secondary mb-1 block text-sm font-medium"
            >
              Confirm new password
            </label>
            <input
              id="confirm-password"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              required
              minLength={8}
              className="bg-bg-tertiary border-border text-text-primary focus:border-accent min-h-11 w-full rounded-md border px-3 py-2 text-sm focus:outline-none"
            />
          </div>

          {passwordError && <div className="text-error text-sm">{passwordError}</div>}
          {passwordSuccess && (
            <div className="text-accent text-sm">Password changed successfully.</div>
          )}

          <button
            type="submit"
            disabled={isChangingPassword || !currentPassword || !newPassword || !confirmPassword}
            className="bg-accent text-bg-primary hover:bg-accent/90 flex min-h-11 items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
          >
            <KeyRound className={`h-4 w-4 ${isChangingPassword ? 'animate-pulse' : ''}`} />
            {isChangingPassword ? 'Changing…' : 'Change password'}
          </button>
        </form>
      </section>

      <section>
        <h2 className="text-text-secondary mb-4 flex items-center gap-2 text-sm font-medium tracking-wide uppercase">
          <LogOut className="h-4 w-4" />
          Account
        </h2>

        {confirmLogout ? (
          <div className="bg-error/10 border-error/30 rounded-lg border p-4">
            <div className="font-medium">Sign out?</div>
            <div className="text-text-secondary mb-3 text-sm">
              You will need your username and password to sign back in.
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => {
                  logout();
                }}
                className="bg-error/20 text-error hover:bg-error/30 flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors"
              >
                <LogOut className="h-4 w-4" />
                Sign out
              </button>
              <button
                onClick={() => setConfirmLogout(false)}
                className="bg-bg-tertiary text-text-primary hover:bg-bg-hover rounded-md px-4 py-2 text-sm transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <button
            onClick={() => setConfirmLogout(true)}
            className="bg-bg-secondary hover:bg-bg-hover border-border flex w-full items-center gap-3 rounded-lg border p-4 text-left transition-colors"
          >
            <LogOut className="text-error h-5 w-5 shrink-0" />
            <div>
              <div className="font-medium">Sign out</div>
              <div className="text-text-secondary text-sm">Sign out of your account</div>
            </div>
          </button>
        )}
      </section>

      <TrackUploadDialog
        isOpen={isUploadOpen}
        onClose={() => setIsUploadOpen(false)}
        onUploadSuccess={handleUploadSuccess}
      />
    </div>
  );
}
