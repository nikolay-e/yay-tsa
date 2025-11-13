/**
 * HTML5 Audio implementation of AudioEngine
 * Uses native browser <audio> element for playback
 */

import { AudioEngine } from '../audio.interface.js';

export class HTML5AudioEngine implements AudioEngine {
  private audio: HTMLAudioElement;
  private _isPlaying: boolean = false;
  private audioContext: AudioContext | null = null;

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

  // Promise chain for serializing operations (prevents race conditions)
  private playPromiseChain: Promise<void> = Promise.resolve();

  // Track current load operation for cancellation (memory leak prevention)
  private currentLoadReject: ((error: Error) => void) | null = null;
  private loadCancelled: boolean = false;

  constructor() {
    this.audio = new Audio();
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
      } catch {
        // Audio context creation failed - not critical, fallback to basic audio
        // Silently ignore - basic audio playback will still work
      }
    }
  }

  async load(url: string): Promise<void> {
    // Cancel previous load operation (silent cancellation)
    if (this.currentLoadReject) {
      this.loadCancelled = true;
      this.currentLoadReject(new Error('Load cancelled - new track requested'));
      this.currentLoadReject = null;
    }

    return new Promise((resolve, reject) => {
      // Reset cancellation flag for new load
      this.loadCancelled = false;
      this.currentLoadReject = reject;

      const handleCanPlay = () => {
        // Skip if already cancelled
        if (this.loadCancelled) return;

        this.audio.removeEventListener('canplay', handleCanPlay);
        this.audio.removeEventListener('error', handleError);
        this.currentLoadReject = null;
        resolve();
      };

      const handleError = () => {
        // Skip if already cancelled
        if (this.loadCancelled) return;

        this.audio.removeEventListener('canplay', handleCanPlay);
        this.audio.removeEventListener('error', handleError);
        this.currentLoadReject = null;
        const mediaError = this.audio.error;
        const error = mediaError
          ? new Error(`Failed to load audio: ${mediaError.message || 'Unknown error'}`)
          : new Error('Failed to load audio');
        reject(error);
      };

      this.audio.addEventListener('canplay', handleCanPlay, { once: true });
      this.audio.addEventListener('error', handleError, { once: true });

      this.audio.src = url;
      this.audio.load();
    });
  }

  async play(): Promise<void> {
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
            throw new Error(`Failed to play audio: ${err.message}`);
          }
          // AbortError and NotAllowedError are expected - don't throw
        }
      })
      .catch((error) => {
        // Catch to prevent unhandled rejection, but don't rethrow
        if ((error as Error).name !== 'AbortError') {
          console.warn('Play failed:', error);
        }
      });

    return this.playPromiseChain;
  }

  pause(): void {
    // Serialize pause operations too
    this.playPromiseChain = this.playPromiseChain
      .then(() => {
        this.audio.pause();
      })
      .catch(() => {
        // Silent catch
      });
  }

  seek(seconds: number): void {
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
    // Clamp between 0 and 1
    this.audio.volume = Math.max(0, Math.min(1, level));
  }

  getCurrentTime(): number {
    return this.audio.currentTime;
  }

  getDuration(): number {
    return this.audio.duration || 0;
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
      const error = mediaError
        ? new Error(`Audio error: ${mediaError.message || 'Unknown error'}`)
        : new Error('Unknown audio error');
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
}
