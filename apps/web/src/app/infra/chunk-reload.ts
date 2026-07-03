const RELOADED_KEY = 'yaytsa_chunk_preload_reloaded';
// Guards against a reload loop if the deploy that caused the stale-chunk fetch is
// itself broken (every chunk 404s again post-reload). Cleared a few seconds after
// a clean boot so a LATER, unrelated deploy can still trigger one more recovery.
const GUARD_CLEAR_DELAY_MS = 5000;

export function installChunkReloadRecovery(): void {
  window.addEventListener('vite:preloadError', () => {
    if (sessionStorage.getItem(RELOADED_KEY)) return;
    sessionStorage.setItem(RELOADED_KEY, '1');
    window.location.reload();
  });
  setTimeout(() => sessionStorage.removeItem(RELOADED_KEY), GUARD_CLEAR_DELAY_MS);
}
