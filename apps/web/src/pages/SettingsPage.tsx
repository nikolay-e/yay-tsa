import { useState, useEffect } from 'react';
import { RefreshCw, HardDrive, Info, Server, LogOut, Upload, Radio, Play, Square } from 'lucide-react';
import { AdminService, RadioService, MediaServerError } from '@yay-tsa/core';
import { queryClient } from '@/shared/lib/query-client';
import { useAuthStore, useIsAdmin } from '@/features/auth/stores/auth.store';
import { TrackUploadDialog } from '@/features/library/components';
import { VersionInfo } from '@/shared/components/VersionInfo';
import { useAnalysisStats } from '@/features/radio/hooks/useRadio';

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
  const isAdmin = useIsAdmin();
  const [status, setStatus] = useState<string | null>(null);
  const [isRescanning, setIsRescanning] = useState(false);
  const [isReloading, setIsReloading] = useState(false);
  const [isUploadOpen, setIsUploadOpen] = useState(false);

  // Smart Radio state
  const [llmProvider, setLlmProvider] = useState('claude');
  const [claudeKey, setClaudeKey] = useState('');
  const [openaiKey, setOpenaiKey] = useState('');
  const [claudeKeyDirty, setClaudeKeyDirty] = useState(false);
  const [openaiKeyDirty, setOpenaiKeyDirty] = useState(false);
  const [radioSaving, setRadioSaving] = useState(false);
  const { data: analysisStats } = useAnalysisStats(isAdmin);

  // Load radio settings
  useEffect(() => {
    if (!client || !isAdmin) return;
    let cancelled = false;
    void (async () => {
      try {
        const resp = await client.get<Record<string, string>>('/Admin/Settings/radio');
        if (!cancelled && resp) {
          setLlmProvider(resp['radio.llm.provider'] || 'claude');
          setClaudeKey(resp['radio.llm.claude.api-key'] || '');
          setOpenaiKey(resp['radio.llm.openai.api-key'] || '');
          setClaudeKeyDirty(false);
          setOpenaiKeyDirty(false);
        }
      } catch {
        // ignore
      }
    })();
    return () => { cancelled = true; };
  }, [client, isAdmin]);

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

  const handleSaveRadioSettings = async () => {
    if (!client) return;
    setRadioSaving(true);
    try {
      const settings: Record<string, string> = {
        'radio.llm.provider': llmProvider,
      };
      if (claudeKeyDirty) {
        settings['radio.llm.claude.api-key'] = claudeKey;
      }
      if (openaiKeyDirty) {
        settings['radio.llm.openai.api-key'] = openaiKey;
      }
      await client.post('/Admin/Settings/radio', settings);
      setStatus('Radio settings saved');
    } catch {
      setStatus('Failed to save radio settings');
    } finally {
      setRadioSaving(false);
    }
  };

  const handleStartAnalysis = async () => {
    if (!client) return;
    try {
      const radioService = new RadioService(client);
      await radioService.startBatchAnalysis();
      setStatus('Analysis started');
      void queryClient.invalidateQueries({ queryKey: ['radio', 'analysisStats'] });
    } catch {
      setStatus('Failed to start analysis');
    }
  };

  const handleStopAnalysis = async () => {
    if (!client) return;
    try {
      const radioService = new RadioService(client);
      await radioService.stopBatchAnalysis();
      setStatus('Analysis stopped');
      void queryClient.invalidateQueries({ queryKey: ['radio', 'analysisStats'] });
    } catch {
      setStatus('Failed to stop analysis');
    }
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
            <Radio className="h-4 w-4" />
            Smart Radio
          </h2>

          <div className="bg-bg-secondary border-border space-y-4 rounded-lg border p-4">
            {/* LLM Provider */}
            <div>
              <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                LLM Provider
              </label>
              <select
                value={llmProvider}
                onChange={e => setLlmProvider(e.target.value)}
                className="bg-bg-tertiary border-border text-text-primary w-full rounded-md border px-3 py-2 text-sm"
              >
                <option value="claude">Claude (Anthropic)</option>
                <option value="openai">OpenAI (GPT)</option>
              </select>
            </div>

            {/* Claude API Key */}
            <div>
              <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                Claude API Key
              </label>
              <input
                type="password"
                value={claudeKey}
                onChange={e => { setClaudeKey(e.target.value); setClaudeKeyDirty(true); }}
                placeholder="sk-ant-..."
                className="bg-bg-tertiary border-border text-text-primary w-full rounded-md border px-3 py-2 text-sm"
              />
            </div>

            {/* OpenAI API Key */}
            <div>
              <label className="text-text-secondary mb-1 block text-xs font-medium uppercase tracking-wide">
                OpenAI API Key
              </label>
              <input
                type="password"
                value={openaiKey}
                onChange={e => { setOpenaiKey(e.target.value); setOpenaiKeyDirty(true); }}
                placeholder="sk-..."
                className="bg-bg-tertiary border-border text-text-primary w-full rounded-md border px-3 py-2 text-sm"
              />
            </div>

            <button
              onClick={() => void handleSaveRadioSettings()}
              disabled={radioSaving}
              className="bg-accent text-text-on-accent hover:bg-accent-hover rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50"
            >
              {radioSaving ? 'Saving...' : 'Save'}
            </button>

            {/* Analysis progress */}
            {analysisStats && (
              <div className="border-border border-t pt-4">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-text-secondary text-sm">Track Analysis</span>
                  <span className="text-text-primary text-sm font-medium">
                    {analysisStats.analyzed} / {analysisStats.total} analyzed
                  </span>
                </div>

                <div className="bg-bg-tertiary mb-3 h-2 overflow-hidden rounded-full">
                  <div
                    className="bg-accent h-full rounded-full transition-all"
                    style={{
                      width: `${analysisStats.total > 0 ? (analysisStats.analyzed / analysisStats.total) * 100 : 0}%`,
                    }}
                  />
                </div>

                <div className="flex gap-2">
                  {analysisStats.batchRunning ? (
                    <button
                      onClick={() => void handleStopAnalysis()}
                      className="bg-error/10 text-error hover:bg-error/20 flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors"
                    >
                      <Square className="h-3 w-3" fill="currentColor" />
                      Stop Analysis
                    </button>
                  ) : (
                    <button
                      onClick={() => void handleStartAnalysis()}
                      disabled={analysisStats.unanalyzed === 0}
                      className="bg-accent/10 text-accent hover:bg-accent/20 flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors disabled:opacity-50"
                    >
                      <Play className="h-3 w-3" fill="currentColor" />
                      Start Analysis ({analysisStats.unanalyzed} remaining)
                    </button>
                  )}
                </div>

                {analysisStats.batchRunning && (
                  <p className="text-text-secondary mt-2 text-xs animate-pulse">
                    Analyzing tracks... This may take a while.
                  </p>
                )}
              </div>
            )}
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
