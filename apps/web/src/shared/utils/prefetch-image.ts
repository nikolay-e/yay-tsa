const warmedUrls = new Set<string>();

function isDataSaverEnabled(): boolean {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  return connection?.saveData === true;
}

export function canPrefetchArtwork(): boolean {
  if (globalThis.window === undefined) return false;
  if (navigator.onLine === false) return false;
  if (isDataSaverEnabled()) return false;
  return true;
}

export function warmImageUrl(url: string): void {
  if (!url || warmedUrls.has(url)) return;
  warmedUrls.add(url);

  const image = new Image();
  image.decoding = 'async';
  image.src = url;
}
