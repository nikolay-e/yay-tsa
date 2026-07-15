export type SpeedScope = 'book' | 'all';

const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const NORMALIZATION_STORAGE_KEY = 'yaytsa_normalization_enabled';
const AUDIOBOOK_SPEED_KEY = 'yaytsa_audiobook_speed';
const bookSpeedKey = (bookId: string) => `yaytsa_book_speed_${bookId}`;

export function clampRate(rate: number): number {
  return Math.max(0.5, Math.min(3, rate));
}

function loadGlobalAudiobookSpeed(): number {
  try {
    const raw = localStorage.getItem(AUDIOBOOK_SPEED_KEY);
    return raw ? clampRate(Number.parseFloat(raw)) : 1;
  } catch {
    return 1;
  }
}

export function resolveAudiobookSpeed(bookId: string): number {
  try {
    const perBook = localStorage.getItem(bookSpeedKey(bookId));
    if (perBook) return clampRate(Number.parseFloat(perBook));
  } catch {
    // Ignore storage errors
  }
  return loadGlobalAudiobookSpeed();
}

export function persistAudiobookSpeed(
  rate: number,
  scope: SpeedScope,
  bookId: string | null
): void {
  try {
    if (scope === 'all') {
      localStorage.setItem(AUDIOBOOK_SPEED_KEY, rate.toString());
    } else if (bookId) {
      localStorage.setItem(bookSpeedKey(bookId), rate.toString());
    }
  } catch {
    // Ignore storage errors
  }
}

export function getSavedVolume(): number {
  try {
    const saved = localStorage.getItem(VOLUME_STORAGE_KEY);
    if (saved) {
      const parsed = Number.parseFloat(saved);
      if (!Number.isNaN(parsed) && parsed >= 0 && parsed <= 1) {
        return parsed;
      }
    }
  } catch {
    // Ignore storage errors
  }
  return 1;
}

export function saveVolume(volume: number): void {
  try {
    localStorage.setItem(VOLUME_STORAGE_KEY, volume.toString());
  } catch {
    // Ignore storage errors
  }
}

export function getSavedNormalizationEnabled(): boolean {
  try {
    return localStorage.getItem(NORMALIZATION_STORAGE_KEY) !== 'false';
  } catch {
    return true;
  }
}

export function saveNormalizationEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(NORMALIZATION_STORAGE_KEY, String(enabled));
  } catch {
    // Ignore storage errors
  }
}
