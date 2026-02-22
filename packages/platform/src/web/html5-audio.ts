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
  private _isPlaying: boolean = false;
  private audioContext: AudioContext | null = null;
  private disposed: boolean = false;
  private readonly loadTimeoutMs: number;

  // WebAudio graph nodes (initialized lazily on first user gesture)
  private sourceNodePrimary: MediaElementAudioSourceNode | null = null;
  private sourceNodeSecondary: MediaElementAudioSourceNode | null = null;
  private elemGainPrimary: GainNode | null = null;
  private elemGainSecondary: GainNode | null = null;
  private masterGain: GainNode | null = null;
  private inputBus: GainNode | null = null;
  private _analyser: AnalyserNode | null = null;
  private webAudioInitialized: boolean = false;
  private storedVolume: number = 1;

  // Preload state for seamless switching
  private preloadedUrl: string | null = null;
  private preloadPromise: Promise<void> | null = null;
  private preloadEventCleanup: (() => void) | null = null;

  // Callback registries for external subscribers
  private timeUpdateCallbacks = new Set<(seconds: number) => void>();
  private endedCallbacks = new Set<() => void>();
  private errorCallbacks = new Set<(error: Error) => void>();
  private loadingCallbacks = new Set<(isLoading: boolean) => void>();

  // Approaching-end detection
  private approachingEndCallbacks = new Set<() => void>();
  private approachingEndThresholdMs: number = 500;
  private approachingEndFired: boolean = false;

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

    if (!this.approachingEndFired && this.approachingEndCallbacks.size > 0) {
      const duration = this.audio.duration;
      if (
        Number.isFinite(duration) &&
        duration > 0 &&
        time >= duration - this.approachingEndThresholdMs / 1000
      ) {
        this.approachingEndFired = true;
        for (const cb of this.approachingEndCallbacks) cb();
      }
    }
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

  // Track element fades for crossfade cancellation (fallback path only)
  private activeElementFades: Set<() => void> = new Set();

  // Vocal removal processor for karaoke mode
  private vocalRemovalProcessor: VocalRemovalProcessor | null = null;
  private karaokeEnabled: boolean = false;

  // AudioContext interrupt recovery cleanup
  private contextRecoveryCleanup: (() => void) | null = null;

  constructor(options: HTML5AudioEngineOptions = {}) {
    this.loadTimeoutMs = options.loadTimeoutMs ?? DEFAULT_LOAD_TIMEOUT_MS;
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.audio.preload = 'auto';

    this.audioSecondary = new Audio();
    this.audioSecondary.crossOrigin = 'anonymous';
    this.audioSecondary.preload = 'auto';
    this.audioSecondary.volume = 0;

    if (typeof document !== 'undefined') {
      this.audio.style.display = 'none';
      this.audioSecondary.style.display = 'none';
      document.body.appendChild(this.audio);
      document.body.appendChild(this.audioSecondary);
    }

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
          this.initWebAudioGraph();
          this.setupContextRecovery(this.audioContext);
        }
      } catch (error) {
        log.warn('AudioContext creation failed, using basic audio', {
          error: String(error),
        });
      }
    }

    return this.audioContext;
  }

  private setupContextRecovery(ctx: AudioContext): void {
    const handleStateChange = () => {
      const state = ctx.state as string;
      if ((state === 'suspended' || state === 'interrupted') && this._isPlaying) {
        log.info('AudioContext interrupted during playback, attempting resume');
        ctx.resume().catch(err => {
          log.warn('AudioContext resume failed on statechange', { error: String(err) });
        });
      }
    };

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        const state = ctx.state as string;
        if (state === 'suspended' || state === 'interrupted') {
          log.info('Page visible with suspended AudioContext, resuming');
          ctx.resume().catch(err => {
            log.warn('AudioContext resume failed on visibilitychange', { error: String(err) });
          });
        }
      }
    };

    ctx.addEventListener('statechange', handleStateChange);
    document.addEventListener('visibilitychange', handleVisibility);

    this.contextRecoveryCleanup = () => {
      ctx.removeEventListener('statechange', handleStateChange);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }

  private initWebAudioGraph(): void {
    if (this.webAudioInitialized || !this.audioContext) return;

    try {
      this.masterGain = this.audioContext.createGain();
      this.masterGain.gain.value = this.storedVolume;

      this.inputBus = this.audioContext.createGain();
      this.inputBus.gain.value = 1;

      // Create source nodes AFTER masterGain so volume is correct immediately
      this.sourceNodePrimary = this.audioContext.createMediaElementSource(this.audio);
      this.sourceNodeSecondary = this.audioContext.createMediaElementSource(this.audioSecondary);

      this.elemGainPrimary = this.audioContext.createGain();
      this.elemGainPrimary.gain.value = 1;

      this.elemGainSecondary = this.audioContext.createGain();
      this.elemGainSecondary.gain.value = 0;

      // Connect the graph:
      // SourceA → ElemGainA ─┐
      //                       ├→ InputBus → MasterGain → Destination
      // SourceB → ElemGainB ─┘
      this.sourceNodePrimary.connect(this.elemGainPrimary);
      this.sourceNodeSecondary.connect(this.elemGainSecondary);
      this.elemGainPrimary.connect(this.inputBus);
      this.elemGainSecondary.connect(this.inputBus);
      this.inputBus.connect(this.masterGain);
      this.masterGain.connect(this.audioContext.destination);

      this._analyser = this.audioContext.createAnalyser();
      this._analyser.fftSize = 2048;
      this.inputBus.connect(this._analyser);

      // Volume now controlled via masterGain; element.volume must be 1
      // (element.volume acts as pre-gain before MediaElementAudioSourceNode)
      this.audio.volume = 1;
      this.audioSecondary.volume = 1;

      this.webAudioInitialized = true;
      log.info('WebAudio graph initialized');

      if (!this.audio.paused && this.audioContext.state === 'suspended') {
        this.audioContext.resume().catch(err => {
          log.warn('AudioContext auto-resume failed', { error: String(err) });
        });
      }
    } catch (error) {
      log.warn('Failed to initialize WebAudio graph, using basic audio', {
        error: String(error),
      });
    }
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

          this.approachingEndFired = false;
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

    const result: { error: Error | null } = { error: null };

    this.playPromiseChain = this.playPromiseChain
      .then(async () => {
        try {
          if (this.audioContext) {
            const state = this.audioContext.state as string;
            if (state === 'suspended' || state === 'interrupted') {
              await this.audioContext.resume();
            }
          }

          await this.audio.play();
        } catch (error) {
          const err = error as Error;
          if (err.name !== 'AbortError') {
            result.error = err;
          }
        }
      })
      .catch(error => {
        result.error = result.error ?? (error as Error);
      });

    await this.playPromiseChain;

    if (result.error) {
      const sanitized = this.sanitizeError(result.error.message);
      throw new Error(`Failed to play audio: ${sanitized}`);
    }
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
    this.approachingEndFired = false;
    const duration = this.getDuration();
    const clampedSeconds = duration > 0 ? Math.min(seconds, duration) : seconds;
    this.audio.currentTime = clampedSeconds;
  }

  setVolume(level: number): void {
    this.ensureNotDisposed();

    const clamped = Math.max(0, Math.min(1, level));
    this.storedVolume = clamped;

    if (this.masterGain && this.audioContext) {
      this.masterGain.gain.setValueAtTime(clamped, this.audioContext.currentTime);
    } else {
      this.audio.volume = clamped;
    }
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
    return this.storedVolume;
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

  onApproachingEnd(callback: () => void, thresholdMs: number = 500): () => void {
    this.approachingEndCallbacks.add(callback);
    this.approachingEndThresholdMs = thresholdMs;
    return () => {
      this.approachingEndCallbacks.delete(callback);
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
    this.approachingEndCallbacks.clear();
    this.errorCallbacks.clear();
    this.loadingCallbacks.clear();

    // Clean up both audio elements
    this.audio.pause();
    this.audio.src = '';
    this.audio.load();
    this.audio.remove();
    this.audioSecondary.pause();
    this.audioSecondary.src = '';
    this.audioSecondary.load();
    this.audioSecondary.remove();

    // Dispose vocal removal processor
    if (this.vocalRemovalProcessor) {
      this.vocalRemovalProcessor.dispose();
      this.vocalRemovalProcessor = null;
    }

    // Disconnect WebAudio nodes (ignore errors from already-disconnected nodes)
    if (this.webAudioInitialized) {
      const nodes = [
        this.sourceNodePrimary,
        this.sourceNodeSecondary,
        this.elemGainPrimary,
        this.elemGainSecondary,
        this.inputBus,
        this.masterGain,
        this._analyser,
      ];
      for (const node of nodes) {
        try {
          node?.disconnect();
        } catch (_) {
          // Node already disconnected
        }
      }
    }
    this.sourceNodePrimary = null;
    this.sourceNodeSecondary = null;
    this.elemGainPrimary = null;
    this.elemGainSecondary = null;
    this.inputBus = null;
    this.masterGain = null;
    this._analyser = null;

    // Remove AudioContext recovery listeners
    if (this.contextRecoveryCleanup) {
      this.contextRecoveryCleanup();
      this.contextRecoveryCleanup = null;
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

  get analyser(): AnalyserNode | null {
    return this._analyser;
  }

  getAudioContext(): AudioContext | null {
    return this.ensureAudioContext();
  }

  getRMS(): number {
    if (!this._analyser) return -1;
    const dataArray = new Float32Array(this._analyser.fftSize);
    this._analyser.getFloatTimeDomainData(dataArray);
    let sum = 0;
    for (let i = 0; i < dataArray.length; i++) {
      sum += dataArray[i] * dataArray[i];
    }
    return Math.sqrt(sum / dataArray.length);
  }

  getAudioElement(): HTMLAudioElement | null {
    return this.audio;
  }

  setKaraokeMode(enabled: boolean): void {
    this.ensureNotDisposed();

    const ctx = this.ensureAudioContext();
    if (!ctx || !this.webAudioInitialized || !this.inputBus || !this.masterGain) {
      log.warn('WebAudio not available for karaoke mode');
      this.karaokeEnabled = false;
      return;
    }

    this.karaokeEnabled = enabled;

    if (enabled && !this.vocalRemovalProcessor) {
      try {
        // Disconnect direct path: inputBus → masterGain
        this.inputBus.disconnect(this.masterGain);

        // Create and insert vocal removal processor between inputBus and masterGain
        this.vocalRemovalProcessor = new VocalRemovalProcessor(ctx, {
          enabled: true,
          bassPreservationCutoff: 120,
        });
        this.vocalRemovalProcessor.connectToGraph(this.inputBus, this.masterGain);
        log.info('Karaoke mode enabled');
      } catch (error) {
        log.error('Failed to enable karaoke mode', { error: String(error) });
        // Restore direct connection on failure
        try {
          this.inputBus.connect(this.masterGain);
        } catch (_) {
          // Best-effort reconnect
        }
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

    // Always preload into secondary element (this.audio is always the active one)
    const targetElement = this.audioSecondary;

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

  isPreloaded(url: string): boolean {
    return this.preloadedUrl === url && this.preloadPromise !== null;
  }

  private performElementSwap(): void {
    const oldElement = this.audio;
    const newElement = this.audioSecondary;

    oldElement.pause();
    oldElement.src = '';
    oldElement.load();

    this.approachingEndFired = false;

    this.audio = newElement;
    this.audioSecondary = oldElement;

    if (this.webAudioInitialized) {
      const tempSource = this.sourceNodePrimary;
      this.sourceNodePrimary = this.sourceNodeSecondary;
      this.sourceNodeSecondary = tempSource;

      const tempGain = this.elemGainPrimary;
      this.elemGainPrimary = this.elemGainSecondary;
      this.elemGainSecondary = tempGain;
    }

    this.detachDispatchHandlers(oldElement);
    this.attachDispatchHandlers(newElement);

    this.preloadedUrl = null;
    this.preloadPromise = null;
  }

  async seamlessSwitch(
    seekPosition: number = 0,
    crossfadeDurationMs: number = 150
  ): Promise<{ duration: number; position: number }> {
    this.ensureNotDisposed();

    if (!this.preloadPromise || !this.preloadedUrl) {
      throw new Error('No audio preloaded. Call preload() first.');
    }

    await this.preloadPromise;

    const currentElement = this.audio;
    const nextElement = this.audioSecondary;
    const newDuration = nextElement.duration;
    const isMidTrackSwitch = seekPosition > 0;

    // Pre-seek to approximate position to prime the buffer region.
    // For next-track (seekPosition=0) this is a no-op.
    if (isMidTrackSwitch && Number.isFinite(newDuration)) {
      nextElement.currentTime = Math.min(seekPosition, newDuration);
    }

    if (!this.webAudioInitialized) {
      nextElement.volume = 0;
    }

    // Start muted playback to prime the decode pipeline.
    // Secondary gain node is already at 0 — no audible output yet.
    await nextElement.play();

    // CRITICAL: For mid-track switches (karaoke), snap to the live position
    // AFTER play() resolved so all async work is done. The only operations
    // between this read and the crossfade ramp are synchronous — zero drift.
    let finalPosition = seekPosition;
    if (isMidTrackSwitch && Number.isFinite(newDuration)) {
      finalPosition = currentElement.currentTime;
      nextElement.currentTime = Math.min(finalPosition, newDuration);
    }

    // Crossfade: everything below is synchronous scheduling — no await gaps.
    if (
      this.webAudioInitialized &&
      this.audioContext &&
      this.elemGainPrimary &&
      this.elemGainSecondary
    ) {
      const now = this.audioContext.currentTime;
      const fadeEnd = now + crossfadeDurationMs / 1000;

      this.elemGainPrimary.gain.cancelScheduledValues(now);
      this.elemGainSecondary.gain.cancelScheduledValues(now);

      this.elemGainPrimary.gain.setValueAtTime(1, now);
      this.elemGainPrimary.gain.linearRampToValueAtTime(0, fadeEnd);

      this.elemGainSecondary.gain.setValueAtTime(0, now);
      this.elemGainSecondary.gain.linearRampToValueAtTime(1, fadeEnd);

      await new Promise<void>(resolve => {
        setTimeout(resolve, crossfadeDurationMs + 20);
      });
    } else {
      const currentVolume = currentElement.volume;
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
    }

    this.performElementSwap();

    log.info('Seamless switch complete', { seekPosition, finalPosition, crossfadeDurationMs });

    return {
      duration: Number.isFinite(newDuration) ? newDuration : 0,
      position: finalPosition,
    };
  }

  async transitionToPreloaded(): Promise<{ duration: number; position: number }> {
    this.ensureNotDisposed();

    if (!this.preloadPromise || !this.preloadedUrl) {
      throw new Error('No audio preloaded. Call preload() first.');
    }

    await this.preloadPromise;

    const nextElement = this.audioSecondary;
    const newDuration = nextElement.duration;

    if (this.webAudioInitialized && this.audioContext && this.elemGainSecondary) {
      this.elemGainSecondary.gain.setValueAtTime(1, this.audioContext.currentTime);
    } else {
      nextElement.volume = this.storedVolume;
    }

    await nextElement.play();

    this.performElementSwap();

    if (this.webAudioInitialized && this.audioContext && this.elemGainPrimary) {
      this.elemGainPrimary.gain.setValueAtTime(1, this.audioContext.currentTime);
    }

    log.info('Instant transition complete');

    return {
      duration: Number.isFinite(newDuration) ? newDuration : 0,
      position: 0,
    };
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

  getActiveAudioElement(): HTMLAudioElement {
    return this.audio;
  }
}
