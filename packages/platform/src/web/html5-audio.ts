/**
 * HTML5 Audio implementation of AudioEngine
 * Uses native browser <audio> element for playback
 */

import { AudioEngine } from '../audio.interface.js';
import { createLogger } from '@yaytsa/core';

const log = createLogger('Audio');

export class HTML5AudioEngine implements AudioEngine {
  private audio: HTMLAudioElement;
  private _isPlaying: boolean = false;
  private audioContext: AudioContext | null = null;
  private disposed: boolean = false;

  // Store event handler references for cleanup
  private handlePlay = () => {
    this._isPlaying = true;
  };
  private handlePause = () => {
    this._isPlaying = false;
  };
  private handleEnded = () => {
    this._isPlaying = false;
  };

  // Promise chains for serializing operations (prevents race conditions)
  private playPromiseChain: Promise<void> = Promise.resolve();
  private loadPromiseChain: Promise<void> = Promise.resolve();

  // Track current load operation for cancellation (memory leak prevention)
  private currentLoadReject: ((error: Error) => void) | null = null;
  private loadCancelled: boolean = false;
  private loadTimeouts: Set<number> = new Set();
  private loadEventCleanup: (() => void) | null = null;

  // Track current fade operation for cancellation
  private currentFadeCancel: (() => void) | null = null;

  constructor() {
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.audio.preload = 'auto';

    // Track playing state
    this.audio.addEventListener('play', this.handlePlay);
    this.audio.addEventListener('pause', this.handlePause);
    this.audio.addEventListener('ended', this.handleEnded);

    // Initialize Audio Context for iOS background playback support
    // This helps ensure iOS properly manages the audio session
    if (
      typeof window !== 'undefined' &&
      ('AudioContext' in window || 'webkitAudioContext' in window)
    ) {
      try {
        const windowWithAudio = window as typeof window & {
          AudioContext?: typeof AudioContext;
          webkitAudioContext?: typeof AudioContext;
        };
        const AudioContextClass =
          windowWithAudio.AudioContext || windowWithAudio.webkitAudioContext;
        if (AudioContextClass) {
          this.audioContext = new AudioContextClass();
        }
      } catch (error) {
        // Audio context creation failed - not critical, fallback to basic audio
        log.warn('AudioContext creation failed, using basic audio', {
          error: String(error),
        });
      }
    }
  }

  private sanitizeError(error: unknown): string {
    const message = error instanceof Error ? error.message : String(error);
    // Remove API keys from error messages to prevent token exposure in logs
    return message.replace(/api_key=[^&\s]+/g, 'api_key=[REDACTED]');
  }

  private ensureNotDisposed(): void {
    if (this.disposed) {
      throw new Error('AudioEngine has been disposed. Create a new instance.');
    }
  }

  private cancelCurrentLoad(): void {
    // Cancel pending load promise
    if (this.currentLoadReject) {
      this.loadCancelled = true;
      this.currentLoadReject(new Error('Load cancelled - new track requested'));
      this.currentLoadReject = null;
    }

    // Clear all pending timeouts
    this.loadTimeouts.forEach(timeoutId => clearTimeout(timeoutId));
    this.loadTimeouts.clear();

    // Clean up event listeners
    if (this.loadEventCleanup) {
      this.loadEventCleanup();
      this.loadEventCleanup = null;
    }
  }

  async load(url: string): Promise<void> {
    this.ensureNotDisposed();

    // Serialize load operations through Promise chain to prevent race conditions
    this.loadPromiseChain = this.loadPromiseChain
      .then(async () => {
        // Cancel previous load operation (silent cancellation)
        this.cancelCurrentLoad();

        // Load the track
        return new Promise<void>((resolve, reject) => {
          // Reset cancellation flag for new load
          this.loadCancelled = false;
          this.currentLoadReject = reject;

          // Firefox race condition: already loaded
          if (
            this.audio.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
            this.audio.src === url
          ) {
            this.currentLoadReject = null;
            resolve();
            return;
          }

          // Use 'canplaythrough' for smoother playback - more buffering before start
          // This prevents stuttering on slower/unstable connections
          const handleCanPlayThrough = () => {
            if (this.loadCancelled) return;
            cleanup();
            this.currentLoadReject = null;
            resolve();
          };

          // Fallback: if canplaythrough takes too long, use canplay after timeout
          const handleCanPlay = () => {
            if (this.loadCancelled) return;
            // Start a timeout - if canplaythrough doesn't fire in 2s, proceed with canplay
            const timeoutId = window.setTimeout(() => {
              this.loadTimeouts.delete(timeoutId);
              if (this.loadCancelled) return;
              if (this.currentLoadReject === reject) {
                cleanup();
                this.currentLoadReject = null;
                resolve();
              }
            }, 2000);
            this.loadTimeouts.add(timeoutId);
          };

          const handleError = () => {
            if (this.loadCancelled) return;
            cleanup();
            this.currentLoadReject = null;
            const mediaError = this.audio.error;
            const errorMessage = mediaError?.message || 'Unknown error';
            const sanitized = this.sanitizeError(errorMessage);
            reject(new Error(`Failed to load audio: ${sanitized}`));
          };

          const cleanup = () => {
            this.audio.removeEventListener('canplaythrough', handleCanPlayThrough);
            this.audio.removeEventListener('canplay', handleCanPlay);
            this.audio.removeEventListener('error', handleError);
            this.loadEventCleanup = null;
          };

          // Store cleanup function for cancellation
          this.loadEventCleanup = cleanup;

          this.audio.addEventListener('canplaythrough', handleCanPlayThrough, { once: true });
          this.audio.addEventListener('canplay', handleCanPlay, { once: true });
          this.audio.addEventListener('error', handleError, { once: true });

          this.audio.src = url;
          this.audio.load();
        });
      })
      .catch(error => {
        // Propagate errors from Web Audio setup or load operation
        throw error;
      });

    return this.loadPromiseChain;
  }

  async play(): Promise<void> {
    this.ensureNotDisposed();

    // Serialize play operations through Promise chain
    this.playPromiseChain = this.playPromiseChain
      .then(async () => {
        try {
          // Resume Audio Context if suspended (iOS requirement for background playback)
          if (this.audioContext?.state === 'suspended') {
            await this.audioContext.resume();
          }

          await this.audio.play();
        } catch (error) {
          // Distinguish AbortError from real errors
          const err = error as Error;
          if (err.name !== 'AbortError' && err.name !== 'NotAllowedError') {
            const sanitized = this.sanitizeError(err.message);
            throw new Error(`Failed to play audio: ${sanitized}`);
          }
          // AbortError and NotAllowedError are expected - don't throw
        }
      })
      .catch(error => {
        // Catch to prevent unhandled rejection, but don't rethrow
        if ((error as Error).name !== 'AbortError') {
          const sanitized = this.sanitizeError(error);
          log.warn('Play failed', { error: sanitized });
        }
      });

    return this.playPromiseChain;
  }

  pause(): void {
    this.ensureNotDisposed();

    // Serialize pause operations too
    this.playPromiseChain = this.playPromiseChain
      .then(() => {
        this.audio.pause();
      })
      .catch(error => {
        // Pause errors are usually due to race conditions with play()
        // Log at debug level as they're typically not actionable
        log.debug('Pause operation failed', { error: String(error) });
      });
  }

  seek(seconds: number): void {
    this.ensureNotDisposed();

    if (!Number.isFinite(seconds)) {
      throw new Error(`Invalid seek position: ${seconds} (must be a finite number)`);
    }
    if (seconds < 0) {
      throw new Error(`Invalid seek position: ${seconds} (cannot be negative)`);
    }
    const duration = this.getDuration();
    const clampedSeconds = duration > 0 ? Math.min(seconds, duration) : seconds;
    this.audio.currentTime = clampedSeconds;
  }

  setVolume(level: number): void {
    this.ensureNotDisposed();

    // Clamp between 0 and 1
    this.audio.volume = Math.max(0, Math.min(1, level));
  }

  getCurrentTime(): number {
    return this.audio.currentTime;
  }

  getDuration(): number {
    const duration = this.audio.duration;
    // Validate duration is a finite positive number
    if (!Number.isFinite(duration) || duration < 0) {
      return 0;
    }
    return duration;
  }

  getVolume(): number {
    return this.audio.volume;
  }

  isPlaying(): boolean {
    return this._isPlaying;
  }

  onTimeUpdate(callback: (seconds: number) => void): () => void {
    const handler = () => callback(this.audio.currentTime);
    this.audio.addEventListener('timeupdate', handler);
    return () => this.audio.removeEventListener('timeupdate', handler);
  }

  onEnded(callback: () => void): () => void {
    const handler = () => callback();
    this.audio.addEventListener('ended', handler);
    return () => this.audio.removeEventListener('ended', handler);
  }

  onError(callback: (error: Error) => void): () => void {
    const handler = () => {
      const mediaError = this.audio.error;
      const errorMessage = mediaError?.message || 'Unknown error';
      const sanitized = this.sanitizeError(errorMessage);
      const error = new Error(`Audio error: ${sanitized}`);
      callback(error);
    };
    this.audio.addEventListener('error', handler);
    return () => this.audio.removeEventListener('error', handler);
  }

  onLoading(callback: (isLoading: boolean) => void): () => void {
    const handleWaiting = () => callback(true);
    const handleCanPlay = () => callback(false);
    const handlePlaying = () => callback(false);

    this.audio.addEventListener('waiting', handleWaiting);
    this.audio.addEventListener('canplay', handleCanPlay);
    this.audio.addEventListener('playing', handlePlaying);

    return () => {
      this.audio.removeEventListener('waiting', handleWaiting);
      this.audio.removeEventListener('canplay', handleCanPlay);
      this.audio.removeEventListener('playing', handlePlaying);
    };
  }

  dispose(): void {
    if (this.disposed) return; // Guard against double-dispose
    this.disposed = true;

    // Cancel any ongoing fade
    if (this.currentFadeCancel) {
      this.currentFadeCancel();
      this.currentFadeCancel = null;
    }

    // Cancel pending load operations
    this.cancelCurrentLoad();

    // Remove event listeners to prevent memory leaks
    this.audio.removeEventListener('play', this.handlePlay);
    this.audio.removeEventListener('pause', this.handlePause);
    this.audio.removeEventListener('ended', this.handleEnded);

    // Clean up audio element
    this.audio.pause();
    this.audio.src = '';
    this.audio.load();

    // Close audio context
    if (this.audioContext && this.audioContext.state !== 'closed') {
      void this.audioContext.close();
    }
  }

  fadeVolume(
    fromLevel: number,
    toLevel: number,
    durationMs: number
  ): { promise: Promise<void>; cancel: () => void } {
    this.ensureNotDisposed();

    // Cancel any existing fade operation
    if (this.currentFadeCancel) {
      this.currentFadeCancel();
    }

    const startTime = Date.now();
    const startVolume = Math.max(0, Math.min(1, fromLevel));
    const endVolume = Math.max(0, Math.min(1, toLevel));
    let cancelled = false;
    let intervalId: ReturnType<typeof setInterval> | null = null;

    const cancel = () => {
      cancelled = true;
      if (intervalId) {
        clearInterval(intervalId);
        intervalId = null;
      }
      if (this.currentFadeCancel === cancel) {
        this.currentFadeCancel = null;
      }
    };

    this.currentFadeCancel = cancel;

    const promise = new Promise<void>(resolve => {
      // Set initial volume
      this.setVolume(startVolume);

      // Use setInterval for smooth fade (~60fps equivalent)
      const FADE_INTERVAL_MS = 16;
      intervalId = setInterval(() => {
        if (cancelled) {
          resolve();
          return;
        }

        const elapsed = Date.now() - startTime;
        const progress = Math.min(elapsed / durationMs, 1);

        // Use ease-in-out curve for smoother fade (less jarring for sleep)
        const easedProgress =
          progress < 0.5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2;

        const currentVolume = startVolume + (endVolume - startVolume) * easedProgress;
        this.setVolume(currentVolume);

        if (progress >= 1) {
          if (intervalId) {
            clearInterval(intervalId);
            intervalId = null;
          }
          this.currentFadeCancel = null;
          resolve();
        }
      }, FADE_INTERVAL_MS);
    });

    return { promise, cancel };
  }

  getAudioContext(): AudioContext | null {
    return this.audioContext;
  }
}
