import { extractPaletteFromPixels, type ThemePalette } from './color-palette';

export type { ThemePalette };

const colorCache = new Map<string, ThemePalette>();
const MAX_CACHE = 200;

const DEFAULT_PALETTE: ThemePalette = {
  bgPrimary: '#0f0f0f',
  bgSecondary: '#1a1a1a',
  bgTertiary: '#252525',
  bgHover: '#2a2a2a',
  accent: '#1db954',
  accentHover: '#1ed760',
  border: '#333333',
  textPrimary: '#ffffff',
  textSecondary: '#bababa',
  textTertiary: '#909090',
  textOnAccent: '#000000',
};

export function getDefaultPalette(): ThemePalette {
  return DEFAULT_PALETTE;
}

function cacheSet(key: string, value: ThemePalette) {
  if (colorCache.size >= MAX_CACHE) {
    const oldest = colorCache.keys().next();
    if (!oldest.done) colorCache.delete(oldest.value);
  }
  colorCache.set(key, value);
}

let worker: Worker | null = null;
let workerFailed = false;
let requestId = 0;

function getWorker(): Worker | null {
  if (workerFailed) return null;
  if (!worker) {
    try {
      worker = new Worker(new URL('./color-extraction.worker.ts', import.meta.url), {
        type: 'module',
      });
      worker.onerror = () => {
        workerFailed = true;
        worker = null;
      };
    } catch {
      workerFailed = true;
      return null;
    }
  }
  return worker;
}

function getPixelData(img: HTMLImageElement): Uint8ClampedArray | null {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;

  const scale = Math.min(1, 50 / Math.max(img.naturalWidth, img.naturalHeight));
  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

  try {
    return ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  } catch {
    return null;
  }
}

async function processPixelsInWorker(pixels: Uint8ClampedArray): Promise<ThemePalette> {
  const w = getWorker();
  if (!w) return extractPaletteFromPixels(pixels) ?? DEFAULT_PALETTE;

  const id = ++requestId;
  return new Promise(resolve => {
    const handleMessage = (e: MessageEvent<{ id: number; palette: ThemePalette | null }>) => {
      if (e.data.id !== id) return;
      w.removeEventListener('message', handleMessage);
      resolve(e.data.palette ?? DEFAULT_PALETTE);
    };
    w.addEventListener('message', handleMessage);

    const copy = new Uint8ClampedArray(pixels);
    w.postMessage({ id, pixels: copy }, [copy.buffer]);
  });
}

export async function extractAlbumPalette(imageUrl: string): Promise<ThemePalette> {
  const cached = colorCache.get(imageUrl);
  if (cached) return cached;

  return new Promise(resolve => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      const pixels = getPixelData(img);
      if (!pixels) {
        resolve(DEFAULT_PALETTE);
        return;
      }

      void processPixelsInWorker(pixels).then(palette => {
        cacheSet(imageUrl, palette);
        resolve(palette);
      });
    };
    img.onerror = () => resolve(DEFAULT_PALETTE);
    img.src = imageUrl;
  });
}
