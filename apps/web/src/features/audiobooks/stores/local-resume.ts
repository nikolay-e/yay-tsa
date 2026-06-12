// Local-first resume durability for audiobooks. Every material position change is written to
// localStorage immediately and cheaply, independent of the network. This is the primary durability
// layer: it survives a lost server write (tab close, PWA background kill, offline) and a stale
// server cache. Cross-device correctness comes from merging this against the server by `updatedAt`
// (last-write-wins) when the audiobook shelf loads — see useAudiobooks.

import { create } from 'zustand';

const KEY_PREFIX = 'yaytsa_resume:';

export interface LocalResume {
  itemId: string;
  positionMs: number;
  runTimeMs: number;
  updatedAt: string;
}

// A monotonic signal bumped on discrete resume changes (seek, pause, chapter/book switch) so the
// audiobook shelf re-derives its merge from the freshest localStorage instead of a snapshot taken
// when the page mounted. Without this, resuming a book you advanced without leaving the page would
// restart from the stale (often zero) position. Not bumped on the periodic heartbeat to avoid churn.
export const useResumeVersion = create<{ value: number; bump: () => void }>(set => ({
  value: 0,
  bump: () => set(state => ({ value: state.value + 1 })),
}));

export function bumpResumeVersion(): void {
  useResumeVersion.getState().bump();
}

function key(itemId: string): string {
  return `${KEY_PREFIX}${itemId}`;
}

// atMs is the instant the position was last live (engine tick/seek), defaulting to now. Writes are
// monotonic by that truth time: a stale background tab flushing an old position on teardown must
// not clobber a fresher record written by the tab the user actually listened in.
export function writeLocalResume(
  itemId: string,
  positionSeconds: number,
  durationSeconds: number,
  atMs: number = Date.now()
): void {
  if (!itemId || atMs <= 0) return;
  try {
    const existing = readLocalResume(itemId);
    if (existing && Date.parse(existing.updatedAt) > atMs) return;
    const record: LocalResume = {
      itemId,
      positionMs: Math.max(0, Math.round(positionSeconds * 1000)),
      runTimeMs: durationSeconds > 0 ? Math.round(durationSeconds * 1000) : 0,
      updatedAt: new Date(atMs).toISOString(),
    };
    localStorage.setItem(key(itemId), JSON.stringify(record));
  } catch {
    // Storage full / disabled — the network path remains as a fallback.
  }
}

export function readLocalResume(itemId: string): LocalResume | null {
  try {
    const raw = localStorage.getItem(key(itemId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as LocalResume;
    if (typeof parsed.positionMs !== 'number' || typeof parsed.updatedAt !== 'string') return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearLocalResume(itemId: string): void {
  try {
    localStorage.removeItem(key(itemId));
  } catch {
    // Ignore storage errors.
  }
}
