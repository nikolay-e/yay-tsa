const SAMPLE_COUNT = 8;
const OUTLIER_TRIM = 0.25;
const EMA_ALPHA = 0.3;
const RESYNC_INTERVAL_MS = 30_000;

export class ServerClock {
  private offset = 0;
  private medianRtt = 0;
  private initialized = false;
  private intervalId: ReturnType<typeof setInterval> | null = null;
  private readonly timeUrl: string;

  constructor(timeUrl: string) {
    this.timeUrl = timeUrl;
  }

  async start(): Promise<void> {
    await this.sync();
    this.intervalId = setInterval(() => {
      this.sync().catch(() => {});
    }, RESYNC_INTERVAL_MS);

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisibilityChange);
    }
  }

  stop(): void {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
    }
  }

  serverNow(): number {
    return performance.now() + this.offset;
  }

  getOffset(): number {
    return this.offset;
  }

  getRtt(): number {
    return this.medianRtt;
  }

  isReady(): boolean {
    return this.initialized;
  }

  async sync(): Promise<void> {
    const samples: Array<{ rtt: number; offset: number }> = [];

    for (let i = 0; i < SAMPLE_COUNT; i++) {
      try {
        const t0 = performance.now();
        const response = await fetch(this.timeUrl);
        const t1 = performance.now();
        const serverMs = Number(await response.text());

        if (Number.isNaN(serverMs)) continue;

        const rtt = t1 - t0;
        const clientMid = t0 + rtt / 2;
        const sampleOffset = serverMs - clientMid;
        samples.push({ rtt, offset: sampleOffset });
      } catch {
        // skip failed sample
      }
    }

    if (samples.length < 3) return;

    samples.sort((a, b) => a.rtt - b.rtt);
    const trimCount = Math.floor(samples.length * OUTLIER_TRIM);
    const trimmed = samples.slice(trimCount, samples.length - trimCount);

    if (trimmed.length === 0) return;

    const medianRttSample = trimmed[Math.floor(trimmed.length / 2)];
    this.medianRtt = medianRttSample.rtt;

    trimmed.sort((a, b) => a.offset - b.offset);
    const medianOffset = trimmed[Math.floor(trimmed.length / 2)].offset;

    if (this.initialized) {
      this.offset = this.offset * (1 - EMA_ALPHA) + medianOffset * EMA_ALPHA;
    } else {
      this.offset = medianOffset;
      this.initialized = true;
    }
  }

  private readonly onVisibilityChange = (): void => {
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
      this.sync().catch(() => {});
    }
  };
}
