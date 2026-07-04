const RELOADED_AT_KEY = 'yaytsa_chunk_preload_reloaded_at';
// Guards against a reload loop if the deploy that caused the stale-chunk fetch is
// itself broken (every chunk 404s again post-reload). The guard is keyed off the
// timestamp of the LAST reload attempt (not this boot's start time) so a fast
// reload+reboot cycle can't shrink the effective loop-prevention window below
// GUARD_WINDOW_MS.
const GUARD_WINDOW_MS = 5000;

export function installChunkReloadRecovery(): void {
  globalThis.addEventListener('vite:preloadError', () => {
    const lastReloadAt = Number(sessionStorage.getItem(RELOADED_AT_KEY) ?? 0);
    if (Date.now() - lastReloadAt < GUARD_WINDOW_MS) return;
    sessionStorage.setItem(RELOADED_AT_KEY, String(Date.now()));
    globalThis.location.reload();
  });
}
