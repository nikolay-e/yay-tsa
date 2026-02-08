/**
 * HTML5 Audio implementation of AudioEngine
 * Uses native browser <audio> element for playback
 */

import { createLogger } from '@yay-tsa/core';
import { type AudioEngine } from '../audio.interface.js';
import { easeInOutQuad } from '../shared/easing.js';
import { VocalRemovalProcessor } from './vocal-removal.js';

const log = createLogger('Audio');

export interface HTML5AudioEngineOptions {
  loadTimeoutMs?: number;
}

const DEFAULT_LOAD_TIMEOUT_MS = 30000;

export class HTML5AudioEngine implements AudioEngine {
  private audio: HTMLAudioElement;
  private audioSecondary: HTMLAudioElement;
  private activeElement: 'primary' | 'secondary' = 'primary';
  private _isPlaying: boolean = false;
  private audioContext: AudioContext | null = null;
  private disposed: boolean = false;
  private readonly loadTimeoutMs: number;

  // Preload state for seamless switching
  private preloadedUrl: string | null = null;
  private preloadPromise: Promise<void> | null = null;
  private preloadEventCleanup: (() => void) | null = null;

  // Callback registries for external subscribers
  private timeUpdateCallbacks = new Set<(seconds: number) => void>();
  private endedCallbacks = new Set<() => void>();
  private errorCallbacks = new Set<(error: Error) => void>();
  private loadingCallbacks = new Set<(isLoading: boolean) => void>();

  // Dispatch handlers — single handler per event type, iterates registered callbacks
  private dispatchPlay = () => {
    this._isPlaying = true;
  };
  private dispatchPause = () => {
    this._isPlaying = false;
  };
  private dispatchEnded = () => {
    this._isPlaying = false;
    for (const cb of this.endedCallbacks) cb();
  };
  private dispatchTimeUpdate = () => {
    const time = this.audio.currentTime;
    for (const cb of this.timeUpdateCallbacks) cb(time);
  };
  private dispatchError = () => {
    const mediaError = this.audio.error;
    const errorMessage = mediaError?.message ?? 'Unknown error';
    const sanitized = this.sanitizeError(errorMessage);
    const error = new Error(`Audio error: ${sanitized}`);
    for (const cb of this.errorCallbacks) cb(error);
  };
  private dispatchWaiting = () => {
    for (const cb of this.loadingCallbacks) cb(true);
  };
  private dispatchCanPlay = () => {
    for (const cb of this.loadingCallbacks) cb(false);
  };
  private dispatchPlaying = () => {
    for (const cb of this.loadingCallbacks) cb(false);
  };

  // Promise chains for serializing operations (prevents race conditions)
  private playPromiseChain: Promise<void> = Promise.resolve();
  private loadPromiseChain: Promise<void> = Promise.resolve();

  // Track current load operation for cancellation (memory leak prevention)
  private currentLoadReject: ((error: Error) => void) | null = null;
  private loadCancelled: boolean = false;
  private loadEventCleanup: (() => void) | null = null;

  // Track current fade operation for cancellation
  private currentFadeCancel: (() => void) | null = null;

  // Track element fades for crossfade cancellation
  private activeElementFades: Set<() => void> = new Set();

  // Vocal removal processor for karaoke mode
  private vocalRemovalProcessor: VocalRemovalProcessor | null = null;
  private karaokeEnabled: boolean = false;

  constructor(options: HTML5AudioEngineOptions = {}) {
    this.loadTimeoutMs = options.loadTimeoutMs ?? DEFAULT_LOAD_TIMEOUT_MS;
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.audio.preload = 'auto';

    this.audioSecondary = new Audio();
    this.audioSecondary.crossOrigin = 'anonymous';
    this.audioSecondary.preload = 'auto';
    this.audioSecondary.volume = 0;

    this.attachDispatchHandlers(this.audio);
  }

  private attachDispatchHandlers(element: HTMLAudioElement): void {
    element.addEventListener('play', this.dispatchPlay);
    element.addEventListener('pause', this.dispatchPause);
    element.addEventListener('ended', this.dispatchEnded);
    element.addEventListener('timeupdate', this.dispatchTimeUpdate);
    element.addEventListener('error', this.dispatchError);
    element.addEventListener('waiting', this.dispatchWaiting);
    element.addEventListener('canplay', this.dispatchCanPlay);
    element.addEventListener('playing', this.dispatchPlaying);
  }

  private detachDispatchHandlers(element: HTMLAudioElement): void {
    element.removeEventListener('play', this.dispatchPlay);
    element.removeEventListener('pause', this.dispatchPause);
    element.removeEventListener('ended', this.dispatchEnded);
    element.removeEventListener('timeupdate', this.dispatchTimeUpdate);
    element.removeEventListener('error', this.dispatchError);
    element.removeEventListener('waiting', this.dispatchWaiting);
    element.removeEventListener('canplay', this.dispatchCanPlay);
    element.removeEventListener('playing', this.dispatchPlaying);
  }

  private ensureAudioContext(): AudioContext | null {
    if (this.audioContext) {
      return this.audioContext;
    }

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
          log.info('AudioContext initialized lazily');
        }
      } catch (error) {
        log.warn('AudioContext creation failed, using basic audio', {
          error: String(error),
        });
      }
    }

    return this.audioContext;
  }

  private sanitizeError(error: unknown): string {
    const message = error instanceof Error ? error.message : String(error);
    return message
      .replace(/api_key=[^&\s]+/gi, 'api_key=[REDACTED]')
      .replace(/token=[^&\s]+/gi, 'token=[REDACTED]');
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
          // Note: this.audio.src is always absolute, url may be relative
          let absoluteUrl: string;
          try {
            absoluteUrl = new URL(url, window.location.href).href;
          } catch {
            this.currentLoadReject = null;
            reject(new Error(`Invalid audio URL: ${this.sanitizeError(url)}`));
            return;
          }
          if (
            this.audio.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
            this.audio.src === absoluteUrl
          ) {
            this.currentLoadReject = null;
            resolve();
            return;
          }

          let timeoutId: ReturnType<typeof setTimeout> | null = null;

          const handleCanPlay = () => {
            if (this.loadCancelled) return;
            cleanup();
            this.currentLoadReject = null;
            resolve();
          };

          const handleError = () => {
            if (this.loadCancelled) return;
            cleanup();
            this.currentLoadReject = null;
            const mediaError = this.audio.error;
            const errorMessage = mediaError?.message ?? 'Unknown error';
            const sanitized = this.sanitizeError(errorMessage);
            reject(new Error(`Failed to load audio: ${sanitized}`));
          };

          const handleTimeout = () => {
            if (this.loadCancelled) return;
            cleanup();
            this.currentLoadReject = null;
            reject(new Error(`Audio load timeout after ${this.loadTimeoutMs / 1000} seconds`));
          };

          const cleanup = () => {
            if (timeoutId) {
              clearTimeout(timeoutId);
              timeoutId = null;
            }
            this.audio.removeEventListener('canplay', handleCanPlay);
            this.audio.removeEventListener('error', handleError);
            this.loadEventCleanup = null;
          };

          timeoutId = setTimeout(handleTimeout, this.loadTimeoutMs);

          // Store cleanup function for cancellation
          this.loadEventCleanup = cleanup;

          this.audio.addEventListener('canplay', handleCanPlay, { once: true });
          this.audio.addEventListener('error', handleError, { once: true });

          this.audio.src = absoluteUrl;
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
          // Lazily initialize and resume AudioContext (iOS/Safari autoplay policy)
          const ctx = this.ensureAudioContext();
          if (ctx) {
            const state = ctx.state as string;
            if (state === 'suspended' || state === 'interrupted') {
              await ctx.resume();
            }
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
    this.timeUpdateCallbacks.add(callback);
    return () => {
      this.timeUpdateCallbacks.delete(callback);
    };
  }

  onEnded(callback: () => void): () => void {
    this.endedCallbacks.add(callback);
    return () => {
      this.endedCallbacks.delete(callback);
    };
  }

  onError(callback: (error: Error) => void): () => void {
    this.errorCallbacks.add(callback);
    return () => {
      this.errorCallbacks.delete(callback);
    };
  }

  onLoading(callback: (isLoading: boolean) => void): () => void {
    this.loadingCallbacks.add(callback);
    return () => {
      this.loadingCallbacks.delete(callback);
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

    // Cancel any element fades (crossfade)
    for (const cancel of this.activeElementFades) {
      cancel();
    }
    this.activeElementFades.clear();

    // Cancel pending load operations
    this.cancelCurrentLoad();

    // Clear preload state
    if (this.preloadEventCleanup) {
      this.preloadEventCleanup();
      this.preloadEventCleanup = null;
    }
    this.preloadedUrl = null;
    this.preloadPromise = null;

    // Remove dispatch handlers from both elements
    this.detachDispatchHandlers(this.audio);
    this.detachDispatchHandlers(this.audioSecondary);

    // Clear callback registries
    this.timeUpdateCallbacks.clear();
    this.endedCallbacks.clear();
    this.errorCallbacks.clear();
    this.loadingCallbacks.clear();

    // Clean up both audio elements
    this.audio.pause();
    this.audio.src = '';
    this.audio.load();
    this.audioSecondary.pause();
    this.audioSecondary.src = '';
    this.audioSecondary.load();

    // Dispose vocal removal processor
    if (this.vocalRemovalProcessor) {
      this.vocalRemovalProcessor.dispose();
      this.vocalRemovalProcessor = null;
    }

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

    const { promise, cancel } = this.createFade(fromLevel, toLevel, durationMs, true, volume =>
      this.setVolume(volume)
    );

    this.currentFadeCancel = cancel;

    return { promise, cancel };
  }

  private createFade(
    fromLevel: number,
    toLevel: number,
    durationMs: number,
    useEasing: boolean,
    setVolume: (v: number) => void
  ): { promise: Promise<void>; cancel: () => void } {
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

    const promise = new Promise<void>(resolve => {
      setVolume(startVolume);

      const FADE_INTERVAL_MS = 16;
      intervalId = setInterval(() => {
        if (cancelled) {
          resolve();
          return;
        }

        const elapsed = Date.now() - startTime;
        const progress = Math.min(elapsed / durationMs, 1);

        const easedProgress = useEasing ? easeInOutQuad(progress) : progress;

        const currentVolume = startVolume + (endVolume - startVolume) * easedProgress;
        setVolume(currentVolume);

        if (progress >= 1) {
          if (intervalId) {
            clearInterval(intervalId);
            intervalId = null;
          }
          if (this.currentFadeCancel === cancel) {
            this.currentFadeCancel = null;
          }
          resolve();
        }
      }, FADE_INTERVAL_MS);
    });

    return { promise, cancel };
  }

  getAudioContext(): AudioContext | null {
    return this.ensureAudioContext();
  }

  getAudioElement(): HTMLAudioElement | null {
    return this.audio;
  }

  setKaraokeMode(enabled: boolean): void {
    this.ensureNotDisposed();

    const ctx = this.ensureAudioContext();
    if (!ctx) {
      log.warn('AudioContext not available for karaoke mode');
      this.karaokeEnabled = false;
      return;
    }

    this.karaokeEnabled = enabled;

    if (enabled && !this.vocalRemovalProcessor) {
      try {
        this.vocalRemovalProcessor = new VocalRemovalProcessor(ctx, {
          enabled: true,
          bassPreservationCutoff: 120,
        });
        this.vocalRemovalProcessor.connectToAudioElement(this.audio);
        log.info('Karaoke mode enabled');
      } catch (error) {
        log.error('Failed to enable karaoke mode', { error: String(error) });
        this.karaokeEnabled = false;
      }
    } else if (this.vocalRemovalProcessor) {
      this.vocalRemovalProcessor.setEnabled(enabled);
      log.info(`Karaoke mode ${enabled ? 'enabled' : 'disabled'}`);
    }
  }

  isKaraokeModeEnabled(): boolean {
    return this.karaokeEnabled;
  }

  /**
   * Preload audio into secondary element for seamless switching.
   * Returns immediately, loading happens in background.
   * Use seamlessSwitch() when ready to switch.
   */
  async preload(url: string): Promise<void> {
    this.ensureNotDisposed();

    // Already preloaded this URL
    if (this.preloadedUrl === url && this.preloadPromise) {
      return this.preloadPromise;
    }

    // Cancel any existing preload operation to prevent race condition
    if (this.preloadEventCleanup) {
      this.preloadEventCleanup();
      this.preloadEventCleanup = null;
    }

    const targetElement = this.activeElement === 'primary' ? this.audioSecondary : this.audio;

    this.preloadedUrl = url;
    this.preloadPromise = new Promise<void>((resolve, reject) => {
      const handleCanPlay = () => {
        cleanup();
        resolve();
      };

      const handleError = () => {
        cleanup();
        this.preloadedUrl = null;
        this.preloadPromise = null;
        const mediaError = targetElement.error;
        const errorMessage = mediaError?.message ?? 'Unknown error';
        reject(new Error(`Failed to preload audio: ${this.sanitizeError(errorMessage)}`));
      };

      const cleanup = () => {
        targetElement.removeEventListener('canplay', handleCanPlay);
        targetElement.removeEventListener('error', handleError);
        this.preloadEventCleanup = null;
      };

      // Store cleanup for cancellation
      this.preloadEventCleanup = cleanup;

      targetElement.addEventListener('canplay', handleCanPlay, { once: true });
      targetElement.addEventListener('error', handleError, { once: true });

      targetElement.src = url;
      targetElement.load();
    });

    return this.preloadPromise;
  }

  /**
   * Check if a URL is preloaded and ready for seamless switch
   */
  isPreloaded(url: string): boolean {
    return this.preloadedUrl === url && this.preloadPromise !== null;
  }

  /**
   * Seamlessly switch to preloaded audio with crossfade.
   * No UI blocking - current audio keeps playing during transition.
   * @param seekPosition - Position to seek to in the new stream (for karaoke switching)
   * @param crossfadeDurationMs - Crossfade duration (default 150ms for quick switch)
   */
  async seamlessSwitch(
    seekPosition: number = 0,
    crossfadeDurationMs: number = 150
  ): Promise<{ duration: number }> {
    this.ensureNotDisposed();

    if (!this.preloadPromise || !this.preloadedUrl) {
      throw new Error('No audio preloaded. Call preload() first.');
    }

    // Wait for preload to complete (should be instant if already loaded)
    await this.preloadPromise;

    const currentElement = this.activeElement === 'primary' ? this.audio : this.audioSecondary;
    const nextElement = this.activeElement === 'primary' ? this.audioSecondary : this.audio;

    const wasPlaying = this._isPlaying;
    const currentVolume = currentElement.volume;
    const newDuration = nextElement.duration;

    // Seek to position before starting
    if (seekPosition > 0 && Number.isFinite(newDuration)) {
      nextElement.currentTime = Math.min(seekPosition, newDuration);
    }

    // Set up next element
    nextElement.volume = 0;

    // Start next element if we were playing
    if (wasPlaying) {
      await nextElement.play();
    }

    // Crossfade: fade out current, fade in next
    const fadeOutPromise = this.fadeElementVolume(
      currentElement,
      currentVolume,
      0,
      crossfadeDurationMs
    );
    const fadeInPromise = this.fadeElementVolume(
      nextElement,
      0,
      currentVolume,
      crossfadeDurationMs
    );

    await Promise.all([fadeOutPromise, fadeInPromise]);

    // Stop old element and free memory
    currentElement.pause();
    currentElement.src = '';
    currentElement.load();

    // Swap active element and both audio references
    this.activeElement = this.activeElement === 'primary' ? 'secondary' : 'primary';
    const temp = this.audio;
    this.audio = this.audioSecondary;
    this.audioSecondary = temp;

    // Migrate ALL dispatch handlers from old element to new element
    this.detachDispatchHandlers(currentElement);
    this.attachDispatchHandlers(nextElement);

    // Clear preload state
    this.preloadedUrl = null;
    this.preloadPromise = null;

    log.info('Seamless switch complete', { seekPosition, crossfadeDurationMs });

    return { duration: Number.isFinite(newDuration) ? newDuration : 0 };
  }

  private async fadeElementVolume(
    element: HTMLAudioElement,
    fromLevel: number,
    toLevel: number,
    durationMs: number
  ): Promise<void> {
    const { promise, cancel } = this.createFade(fromLevel, toLevel, durationMs, false, v => {
      element.volume = v;
    });
    this.activeElementFades.add(cancel);
    try {
      await promise;
    } finally {
      this.activeElementFades.delete(cancel);
    }
  }

  /**
   * Get current active audio element (for event handlers)
   */
  getActiveAudioElement(): HTMLAudioElement {
    return this.activeElement === 'primary' ? this.audio : this.audioSecondary;
  }
}
