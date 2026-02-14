import type { AudioEngine } from '@yay-tsa/platform';

export class PreloadManager {
  private state: {
    trackId: string;
    streamUrl: string;
    generation: number;
  } | null = null;

  private generation = 0;

  constructor(private engine: AudioEngine) {}

  prepare(trackId: string, streamUrl: string): void {
    if (this.state?.trackId === trackId) return;

    this.generation++;
    const gen = this.generation;

    this.state = { trackId, streamUrl, generation: gen };

    this.engine.preload?.(streamUrl)?.catch(() => {
      if (this.state?.generation === gen) {
        this.state = null;
      }
    });
  }

  isReady(trackId: string): boolean {
    return this.state?.trackId === trackId && !!this.engine.isPreloaded?.(this.state.streamUrl);
  }

  consume(): string | null {
    if (!this.state) return null;
    const url = this.state.streamUrl;
    this.state = null;
    return url;
  }

  getStreamUrl(): string | null {
    return this.state?.streamUrl ?? null;
  }

  invalidate(): void {
    this.state = null;
    this.generation++;
  }
}
