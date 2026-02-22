import { createLogger } from '@yay-tsa/core';

const log = createLogger('WakeLock');

export class WakeLockManager {
  private sentinel: WakeLockSentinel | null = null;
  private pendingAcquire = false;
  private handleVisibilityChange: (() => void) | null = null;

  async acquire(): Promise<void> {
    if (this.sentinel || !('wakeLock' in navigator)) return;

    try {
      this.sentinel = await navigator.wakeLock.request('screen');
      this.sentinel.addEventListener('release', () => {
        this.sentinel = null;
      });
      log.info('Wake lock acquired');
      this.startVisibilityReacquire();
    } catch (err) {
      log.debug('Wake lock request failed', { error: String(err) });
    }
  }

  release(): void {
    this.stopVisibilityReacquire();
    if (this.sentinel) {
      this.sentinel.release().catch(() => {});
      this.sentinel = null;
      log.info('Wake lock released');
    }
  }

  private startVisibilityReacquire(): void {
    if (this.handleVisibilityChange) return;

    this.handleVisibilityChange = () => {
      if (document.visibilityState === 'visible' && !this.sentinel && !this.pendingAcquire) {
        this.pendingAcquire = true;
        navigator.wakeLock
          .request('screen')
          .then(sentinel => {
            this.sentinel = sentinel;
            sentinel.addEventListener('release', () => {
              this.sentinel = null;
            });
            log.debug('Wake lock re-acquired after visibility change');
          })
          .catch(() => {})
          .finally(() => {
            this.pendingAcquire = false;
          });
      }
    };

    document.addEventListener('visibilitychange', this.handleVisibilityChange);
  }

  private stopVisibilityReacquire(): void {
    if (this.handleVisibilityChange) {
      document.removeEventListener('visibilitychange', this.handleVisibilityChange);
      this.handleVisibilityChange = null;
    }
  }

  dispose(): void {
    this.release();
  }
}
