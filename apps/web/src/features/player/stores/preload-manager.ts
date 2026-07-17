import type { AudioEngine } from '@yay-tsa/platform';

// Starting the next-track preload at t=0 makes three concurrent ~20MB FLAC
// downloads (live stream + preload + listening-cache) fight for bandwidth and
// starve the live buffer — an audible stutter at the start of every track. So
// prepare() only records intent; the real download starts once the current
// track is PRELOAD_START_S in (halfway through for short tracks), when the
// live stream has a healthy buffer.
export const PRELOAD_START_S = 25;

export class PreloadManager {
  private intent: { trackId: string; streamUrl: string } | null = null;

  private started: { trackId: string; streamUrl: string; generation: number } | null = null;

  private generation = 0;

  constructor(private readonly engine: AudioEngine) {}

  prepare(trackId: string, streamUrl: string): void {
    if (this.intent?.trackId === trackId) return;
    this.intent = { trackId, streamUrl };
    if (this.started && this.started.trackId !== trackId) {
      this.started = null;
      this.generation++;
    }
  }

  tick(currentTimeSeconds: number, durationSeconds: number): void {
    if (!this.intent || !Number.isFinite(durationSeconds) || durationSeconds <= 0) return;
    if (this.started?.trackId === this.intent.trackId) return;
    if (currentTimeSeconds < Math.min(PRELOAD_START_S, durationSeconds / 2)) return;

    this.generation++;
    const gen = this.generation;
    this.started = { ...this.intent, generation: gen };

    this.engine.preload?.(this.started.streamUrl)?.catch(() => {
      if (this.started?.generation === gen) {
        this.started = null;
      }
    });
  }

  isReady(trackId: string): boolean {
    return this.started?.trackId === trackId && !!this.engine.isPreloaded?.(this.started.streamUrl);
  }

  consume(): string | null {
    const url = this.started?.streamUrl ?? null;
    this.started = null;
    this.intent = null;
    return url;
  }

  getStreamUrl(): string | null {
    return this.started?.streamUrl ?? this.intent?.streamUrl ?? null;
  }

  invalidate(): void {
    this.intent = null;
    this.started = null;
    this.generation++;
  }
}
