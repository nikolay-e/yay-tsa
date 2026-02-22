interface RGB {
  r: number;
  g: number;
  b: number;
}

interface HSL {
  h: number;
  s: number;
  l: number;
}

export interface ThemePalette {
  bgPrimary: string;
  bgSecondary: string;
  bgTertiary: string;
  bgHover: string;
  accent: string;
  accentHover: string;
  border: string;
  textPrimary: string;
  textSecondary: string;
  textTertiary: string;
  textOnAccent: string;
}

const colorCache = new Map<string, ThemePalette>();
const MAX_CACHE = 200;

function rgbToHsl(r: number, g: number, b: number): HSL {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return { h: 0, s: 0, l };
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = 0;
  if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
  else if (max === g) h = ((b - r) / d + 2) / 6;
  else h = ((r - g) / d + 4) / 6;
  return { h: h * 360, s, l };
}

function hslToHex(h: number, s: number, l: number): string {
  const hue2rgb = (p: number, q: number, t: number) => {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };

  h /= 360;
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const r = Math.round(hue2rgb(p, q, h + 1 / 3) * 255);
  const g = Math.round(hue2rgb(p, q, h) * 255);
  const b = Math.round(hue2rgb(p, q, h - 1 / 3) * 255);
  return `#${((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1)}`;
}

function extractDominantColor(img: HTMLImageElement): RGB {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return { r: 26, g: 26, b: 26 };

  const scale = Math.min(1, 50 / Math.max(img.naturalWidth, img.naturalHeight));
  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

  let pixels: Uint8Array;
  try {
    const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
    pixels = new Uint8Array(data.buffer);
  } catch {
    return { r: 26, g: 26, b: 26 };
  }
  const buckets = new Uint32Array(32768);
  let maxCount = 0;
  let maxKey = 0;

  for (let i = 0; i < pixels.length; i += 4) {
    const r = pixels[i] ?? 0;
    const g = pixels[i + 1] ?? 0;
    const b = pixels[i + 2] ?? 0;
    const a = pixels[i + 3] ?? 0;

    if (a < 128) continue;
    if (r > 240 && g > 240 && b > 240) continue;
    if (r < 15 && g < 15 && b < 15) continue;

    const key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
    const count = (buckets[key] ?? 0) + 1;
    buckets[key] = count;
    if (count > maxCount) {
      maxCount = count;
      maxKey = key;
    }
  }

  if (maxCount === 0) return { r: 26, g: 26, b: 26 };

  return {
    r: ((maxKey >> 10) & 31) * 8 + 4,
    g: ((maxKey >> 5) & 31) * 8 + 4,
    b: (maxKey & 31) * 8 + 4,
  };
}

function relativeLuminance(hex: string): number {
  const toLinear = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;
  return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
}

function buildPalette(hue: number, sat: number): ThemePalette {
  const bgSat = Math.min(0.3, sat * 0.5);
  const accentSat = Math.max(0.5, Math.min(0.85, sat));
  const accentHex = hslToHex(hue, accentSat, 0.48);

  return {
    bgPrimary: hslToHex(hue, bgSat, 0.06),
    bgSecondary: hslToHex(hue, bgSat, 0.1),
    bgTertiary: hslToHex(hue, bgSat, 0.145),
    bgHover: hslToHex(hue, bgSat, 0.17),
    accent: accentHex,
    accentHover: hslToHex(hue, accentSat, 0.55),
    border: hslToHex(hue, bgSat * 0.8, 0.2),
    textPrimary: hslToHex(hue, 0.06, 0.95),
    textSecondary: hslToHex(hue, 0.08, 0.73),
    textTertiary: hslToHex(hue, 0.06, 0.565),
    textOnAccent: relativeLuminance(accentHex) > 0.179 ? '#000000' : '#ffffff',
  };
}

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

export async function extractAlbumPalette(imageUrl: string): Promise<ThemePalette> {
  const cached = colorCache.get(imageUrl);
  if (cached) return Promise.resolve(cached);

  return new Promise(resolve => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      const rgb = extractDominantColor(img);
      const hsl = rgbToHsl(rgb.r, rgb.g, rgb.b);

      if (hsl.s < 0.08) {
        cacheSet(imageUrl, DEFAULT_PALETTE);
        resolve(DEFAULT_PALETTE);
        return;
      }

      const palette = buildPalette(hsl.h, hsl.s);
      cacheSet(imageUrl, palette);
      resolve(palette);
    };
    img.onerror = () => resolve(DEFAULT_PALETTE);
    img.src = imageUrl;
  });
}
