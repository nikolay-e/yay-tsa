/**
 * HTML5 Audio implementation of AudioEngine
 * Uses native browser <audio> element for playback
 */

import { AudioEngine } from '../audio.interface.js';
import { VocalRemovalProcessor } from './vocal-removal.js';
import { createLogger } from '@yaytsa/core';

const log = createLogger('Audio');

export class HTML5AudioEngine implements AudioEngine {
  private audio: HTMLAudioElement;
  private audioSecondary: HTMLAudioElement; // Second element for seamless switching
  private activeElement: 'primary' | 'secondary' = 'primary';
  private _isPlaying: boolean = false;
  private audioContext: AudioContext | null = null;
  private disposed: boolean = false;

  // Preload state for seamless switching
  private preloadedUrl: string | null = null;
  private preloadPromise: Promise<void> | null = null;

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
  private loadEventCleanup: (() => void) | null = null;

  // Track current fade operation for cancellation
  private currentFadeCancel: (() => void) | null = null;

  // Vocal removal processor for karaoke mode
  private vocalRemovalProcessor: VocalRemovalProcessor | null = null;
  private karaokeEnabled: boolean = false;

  constructor() {
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.audio.preload = 'auto';

    // Secondary audio element for seamless switching (karaoke, gapless)
    this.audioSecondary = new Audio();
    this.audioSecondary.crossOrigin = 'anonymous';
    this.audioSecondary.preload = 'auto';
    this.audioSecondary.volume = 0; // Start silent

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

          // Use 'canplay' for instant playback start - minimal buffering delay
          // Trade-off: may have brief stutter on very slow connections, but provides
          // gapless switching experience for track changes and karaoke mode
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
            const errorMessage = mediaError?.message || 'Unknown error';
            const sanitized = this.sanitizeError(errorMessage);
            reject(new Error(`Failed to load audio: ${sanitized}`));
          };

          const cleanup = () => {
            this.audio.removeEventListener('canplay', handleCanPlay);
            this.audio.removeEventListener('error', handleError);
            this.loadEventCleanup = null;
          };

          // Store cleanup function for cancellation
          this.loadEventCleanup = cleanup;

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

    // Clear preload state
    this.preloadedUrl = null;
    this.preloadPromise = null;

    // Remove event listeners to prevent memory leaks (both elements)
    this.audio.removeEventListener('play', this.handlePlay);
    this.audio.removeEventListener('pause', this.handlePause);
    this.audio.removeEventListener('ended', this.handleEnded);
    this.audioSecondary.removeEventListener('play', this.handlePlay);
    this.audioSecondary.removeEventListener('pause', this.handlePause);
    this.audioSecondary.removeEventListener('ended', this.handleEnded);

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

  getAudioElement(): HTMLAudioElement | null {
    return this.audio;
  }

  setKaraokeMode(enabled: boolean): void {
    this.ensureNotDisposed();

    if (!this.audioContext) {
      log.warn('AudioContext not available for karaoke mode');
      this.karaokeEnabled = false;
      return;
    }

    this.karaokeEnabled = enabled;

    if (enabled && !this.vocalRemovalProcessor) {
      try {
        this.vocalRemovalProcessor = new VocalRemovalProcessor(this.audioContext, {
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
        const errorMessage = mediaError?.message || 'Unknown error';
        reject(new Error(`Failed to preload audio: ${this.sanitizeError(errorMessage)}`));
      };

      const cleanup = () => {
        targetElement.removeEventListener('canplay', handleCanPlay);
        targetElement.removeEventListener('error', handleError);
      };

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

    // Stop old element
    currentElement.pause();

    // Swap active element
    this.activeElement = this.activeElement === 'primary' ? 'secondary' : 'primary';

    // Update event listeners to track new active element
    currentElement.removeEventListener('play', this.handlePlay);
    currentElement.removeEventListener('pause', this.handlePause);
    currentElement.removeEventListener('ended', this.handleEnded);

    nextElement.addEventListener('play', this.handlePlay);
    nextElement.addEventListener('pause', this.handlePause);
    nextElement.addEventListener('ended', this.handleEnded);

    // Update internal audio reference
    this.audio = nextElement;

    // Clear preload state
    this.preloadedUrl = null;
    this.preloadPromise = null;

    log.info('Seamless switch complete', { seekPosition, crossfadeDurationMs });

    return { duration: Number.isFinite(newDuration) ? newDuration : 0 };
  }

  /**
   * Fade volume on a specific audio element (for crossfade)
   */
  private async fadeElementVolume(
    element: HTMLAudioElement,
    fromLevel: number,
    toLevel: number,
    durationMs: number
  ): Promise<void> {
    return new Promise(resolve => {
      const startTime = Date.now();
      const startVolume = Math.max(0, Math.min(1, fromLevel));
      const endVolume = Math.max(0, Math.min(1, toLevel));

      element.volume = startVolume;

      const FADE_INTERVAL_MS = 16;
      const intervalId = setInterval(() => {
        const elapsed = Date.now() - startTime;
        const progress = Math.min(elapsed / durationMs, 1);

        // Linear fade for quick crossfade (less noticeable)
        const currentVolume = startVolume + (endVolume - startVolume) * progress;
        element.volume = currentVolume;

        if (progress >= 1) {
          clearInterval(intervalId);
          resolve();
        }
      }, FADE_INTERVAL_MS);
    });
  }

  /**
   * Get current active audio element (for event handlers)
   */
  getActiveAudioElement(): HTMLAudioElement {
    return this.activeElement === 'primary' ? this.audio : this.audioSecondary;
  }
}
