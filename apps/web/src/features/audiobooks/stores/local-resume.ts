// Local-first resume durability for audiobooks. Every material position change is written to
// localStorage immediately and cheaply, independent of the network. This is the primary durability
// layer: it survives a lost server write (tab close, PWA background kill, offline) and a stale
// server cache. Cross-device correctness comes from merging this against the server by `updatedAt`
// (last-write-wins) when the audiobook shelf loads — see useAudiobooks.

const KEY_PREFIX = 'yaytsa_resume:';

export interface LocalResume {
  itemId: string;
  positionMs: number;
  runTimeMs: number;
  updatedAt: string;
}

function key(itemId: string): string {
  return `${KEY_PREFIX}${itemId}`;
}

export function writeLocalResume(
  itemId: string,
  positionSeconds: number,
  durationSeconds: number
): void {
  if (!itemId) return;
  try {
    const record: LocalResume = {
      itemId,
      positionMs: Math.max(0, Math.round(positionSeconds * 1000)),
      runTimeMs: durationSeconds > 0 ? Math.round(durationSeconds * 1000) : 0,
      updatedAt: new Date().toISOString(),
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
