type CommandFactory = (signal: AbortSignal) => Promise<void>;

export class PlaybackController {
  private current: { promise: Promise<void>; abort: AbortController } | null = null;

  async interrupt(factory: CommandFactory): Promise<void> {
    this.cancelCurrent();
    await this.run(factory);
  }

  get isActive(): boolean {
    return this.current !== null;
  }

  async ifIdle(factory: CommandFactory): Promise<void> {
    if (this.current) return;
    await this.run(factory);
  }

  private cancelCurrent(): void {
    if (!this.current) return;
    this.current.abort.abort();
    this.current = null;
  }

  private async run(factory: CommandFactory): Promise<void> {
    const abort = new AbortController();
    const promise = factory(abort.signal);
    this.current = { promise, abort };

    try {
      await promise;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      throw err;
    } finally {
      if (this.current?.promise === promise) {
        this.current = null;
      }
    }
  }
}

export async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Engine timeout after ${ms}ms`)), ms);
    promise.then(
      val => {
        clearTimeout(timer);
        resolve(val);
      },
      err => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}
