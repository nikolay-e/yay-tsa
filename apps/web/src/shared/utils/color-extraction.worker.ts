import { extractPaletteFromPixels, type ThemePalette } from './color-palette';

interface WorkerSelf {
  onmessage: ((e: MessageEvent) => void) | null;
  postMessage(message: unknown, transfer?: Transferable[]): void;
}

const workerSelf = globalThis as unknown as WorkerSelf;

workerSelf.onmessage = (e: MessageEvent<{ id: number; pixels: Uint8ClampedArray }>) => {
  const { id, pixels } = e.data;
  const palette: ThemePalette | null = extractPaletteFromPixels(pixels);
  workerSelf.postMessage({ id, palette });
};
