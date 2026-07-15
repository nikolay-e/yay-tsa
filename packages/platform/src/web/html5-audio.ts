import { createLogger, redactSecrets } from '@yay-tsa/core';
import { type AudioEngine, type MediaPlaybackError } from '../audio.interface.js';
import { createFade } from './fade.js';

const log = createLogger('Audio');

const VOCAL_BLEND_DRIFT_THRESHOLD_SEC = 0.05;
const VOCAL_BLEND_GAIN_SMOOTHING_SEC = 0.015;

export interface HTML5AudioEngineOptions {
  loadTimeoutMs?: number;
  approachingEndThresholdMs?: number;
}

const DEFAULT_LOAD_TIMEOUT_MS = 30000;

export class PreloadSupersededError extends Error {
  constructor(reason: string) {
    super(`Preload superseded - ${reason}`);
    this.name = 'PreloadSupersededError';
  }
}

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
  private normalizationFactor: number = 1;

  // Stable duration captured at load time — immune to browser fluctuations during stalls
  private stableDuration: number = 0;

  // Preload state for seamless switching
  private preloadedUrl: string | null = null;
  private preloadPromise: Promise<void> | null = null;
  private preloadEventCleanup: (() => void) | null = null;
  private preloadReject: ((error: Error) => void) | null = null;

  // Callback registries for external subscribers
  private readonly timeUpdateCallbacks = new Set<(seconds: number) => void>();
  private readonly endedCallbacks = new Set<() => void>();
  private readonly errorCallbacks = new Set<(error: Error) => void>();
  private readonly loadingCallbacks = new Set<(isLoading: boolean) => void>();

  // Approaching-end detection
  private readonly approachingEndCallbacks = new Set<() => void>();
  private readonly approachingEndThresholdMs: number;
  private approachingEndFired: boolean = false;
  // Background tabs throttle `timeupdate`, so we also schedule a `setTimeout`
  // off the active duration. While audio plays the tab is exempt from
  // throttling, so the timer fires reliably and the gapless transition can
  // start before the current track ends.
  private approachingEndTimer: ReturnType<typeof setTimeout> | null = null;

  // Dispatch handlers — single handler per event type, iterates registered callbacks
  private readonly dispatchPlay = () => {
    this._isPlaying = true;
    this.scheduleApproachingEndTimer();
  };
  private readonly dispatchPause = () => {
    this._isPlaying = false;
    this.clearApproachingEndTimer();
  };
  // Element-identity guard: during a gapless swap the outgoing element can still
  // emit events before its handlers are detached; only the active element counts.
  private readonly dispatchEnded = (event: Event) => {
    if (event.target !== this.audio) return;
    this._isPlaying = false;
    this.clearApproachingEndTimer();
    for (const cb of this.endedCallbacks) cb();
  };
  private readonly dispatchTimeUpdate = () => {
    const time = this.audio.currentTime;
    for (const cb of this.timeUpdateCallbacks) cb(time);

    if (!this.approachingEndFired && this.approachingEndCallbacks.size > 0) {
      const duration = this.stableDuration > 0 ? this.stableDuration : this.audio.duration;
      if (
        Number.isFinite(duration) &&
        duration > 0 &&
        time >= duration - this.approachingEndThresholdMs / 1000
      ) {
        this.approachingEndFired = true;
        this.clearApproachingEndTimer();
        for (const cb of this.approachingEndCallbacks) cb();
      }
    }
  };
  private readonly dispatchError = (event: Event) => {
    if (event.target !== this.audio) return;
    const src = this.audio.src;
    const isEmptySrc = src === '' || src === globalThis.window?.location.href;
    const mediaError = this.audio.error;
    const errorMessage = mediaError?.message ?? 'Unknown error';
    if (isEmptySrc || errorMessage.includes('Empty src')) return;
    const sanitized = this.sanitizeError(errorMessage);
    const error: MediaPlaybackError = Object.assign(new Error(`Audio error: ${sanitized}`), {
      mediaErrorCode: mediaError?.code ?? null,
    });
    for (const cb of this.errorCallbacks) cb(error);
  };
  private readonly dispatchWaiting = () => {
    for (const cb of this.loadingCallbacks) cb(true);
  };
  private readonly dispatchCanPlay = () => {
    for (const cb of this.loadingCallbacks) cb(false);
  };
  private readonly dispatchPlaying = () => {
    for (const cb of this.loadingCallbacks) cb(false);
  };
  // Streamed formats (notably FLAC) expose a rough duration at canplay and correct it
  // via durationchange once more of the stream is parsed. Adopt any finite, positive
  // correction so stableDuration converges to the true length; garbage (Infinity/0/NaN
  // during stalls) is ignored, preserving the anti-fluctuation guarantee.
  private readonly dispatchDurationChange = () => {
    const d = this.audio.duration;
    if (Number.isFinite(d) && d > 0) this.stableDuration = d;
  };

  // Promise chains for serializing operations (prevents race conditions)
  private playPromiseChain: Promise<void> = Promise.resolve();
  private loadPromiseChain: Promise<void> = Promise.resolve();

  // Track current load operation for cancellation (memory leak prevention)
  private currentLoadReject: ((error: Error) => void) | null = null;
  private loadCancelled: boolean = false;
  private loadEventCleanup: (() => void) | null = null;
  private loadGeneration = 0;

  // Track current fade operation for cancellation
  private currentFadeCancel: (() => void) | null = null;

  // Track element fades for crossfade cancellation (fallback path only)
  private readonly activeElementFades: Set<() => void> = new Set();

  // Karaoke vocal-blend co-play: primary element plays the instrumental stem at a
  // fixed gain of 1.0, the secondary element plays the vocals stem at a variable gain
  // (0 = pure instrumental/karaoke, 1 = instrumental+vocals sum ≈ original mix). Both
  // elements play simultaneously, time-locked by a drift watchdog.
  private blendActive: boolean = false;
  private vocalBlendLevel: number = 0;
  private blendDriftWatchdog: ReturnType<typeof setInterval> | null = null;

  // AudioContext interrupt recovery cleanup
  private contextRecoveryCleanup: (() => void) | null = null;

  constructor(options: HTML5AudioEngineOptions = {}) {
    this.loadTimeoutMs = options.loadTimeoutMs ?? DEFAULT_LOAD_TIMEOUT_MS;
    this.approachingEndThresholdMs = options.approachingEndThresholdMs ?? 500;
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.audio.preload = 'auto';

    this.audioSecondary = new Audio();
    this.audioSecondary.crossOrigin = 'anonymous';
    this.audioSecondary.preload = 'auto';
    this.audioSecondary.muted = true;

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
    element.addEventListener('durationchange', this.dispatchDurationChange);
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
    element.removeEventListener('durationchange', this.dispatchDurationChange);
  }

  private ensureAudioContext(): AudioContext | null {
    if (this.audioContext) {
      return this.audioContext;
    }

    if (
      globalThis.window !== undefined &&
      ('AudioContext' in globalThis.window || 'webkitAudioContext' in globalThis.window)
    ) {
      try {
        const windowWithAudio = globalThis.window as Window & {
          AudioContext?: typeof AudioContext;
          webkitAudioContext?: typeof AudioContext;
        };
        const AudioContextClass =
          windowWithAudio.AudioContext ?? windowWithAudio.webkitAudioContext;
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
      this.inputBus.gain.value = this.normalizationFactor;

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
    return redactSecrets(message);
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

    // A fresh load always starts in single-stream mode. In karaoke the natural track-end path
    // (preload disabled) reaches load()+play() without going through exitVocalBlend, so without
    // this the secondary element would still hold the previous track's vocals stem and bleed it
    // over the new track. Idempotent: a no-op when no blend is active.
    this.teardownVocalBlend();

    // Cancel the in-flight load synchronously so a skip during a slow load does not
    // have to wait for the previous load to settle before the new src is assigned.
    const generation = ++this.loadGeneration;
    this.cancelCurrentLoad();

    // Serialize load operations through Promise chain to prevent race conditions
    const newLoad = this.loadPromiseChain.then(async () => {
      if (generation !== this.loadGeneration) {
        throw new Error('Load cancelled - new track requested');
      }

      // Load the track
      return new Promise<void>((resolve, reject) => {
        // Reset cancellation flag for new load
        this.loadCancelled = false;
        this.currentLoadReject = reject;

        // Firefox race condition: already loaded
        // Note: this.audio.src is always absolute, url may be relative
        let absoluteUrl: string;
        try {
          absoluteUrl = new URL(url, globalThis.window.location.href).href;
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
          const d = this.audio.duration;
          this.stableDuration = Number.isFinite(d) && d > 0 ? d : 0;
          resolve();
          return;
        }

        let timeoutId: ReturnType<typeof setTimeout> | null = null;

        const handleCanPlay = () => {
          if (this.loadCancelled) return;
          cleanup();
          this.currentLoadReject = null;
          const d = this.audio.duration;
          this.stableDuration = Number.isFinite(d) && d > 0 ? d : 0;
          resolve();
        };

        const handleError = () => {
          if (this.loadCancelled) return;
          cleanup();
          this.currentLoadReject = null;
          const mediaError = this.audio.error;
          const errorMessage = mediaError?.message ?? 'Unknown error';
          const sanitized = this.sanitizeError(errorMessage);
          const error: MediaPlaybackError = Object.assign(
            new Error(`Failed to load audio: ${sanitized}`),
            { mediaErrorCode: mediaError?.code ?? null }
          );
          reject(error);
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
        this.stableDuration = 0;
        this.clearApproachingEndTimer();
        this.audio.src = absoluteUrl;
        this.audio.load();
      });
    });

    // The stored chain only serializes; it must never stay rejected, otherwise one
    // failed load would poison every subsequent load with the stale error.
    this.loadPromiseChain = newLoad.catch(() => {});

    return newLoad.catch(error => {
      if ((error as Error).message?.includes('Load cancelled')) return;
      throw error;
    });
  }

  async play(): Promise<void> {
    this.ensureNotDisposed();

    const result: { error: Error | null } = { error: null };

    this.playPromiseChain = this.playPromiseChain
      .then(async () => {
        try {
          // Resume AudioContext if suspended, but don't block playback on failure.
          // Background tabs may reject resume() — HTMLAudioElement.play() still works
          // and audio will route through AudioContext once it resumes on visibility change.
          if (this.audioContext) {
            const state = this.audioContext.state as string;
            if (state === 'suspended' || state === 'interrupted') {
              this.audioContext.resume().catch(err => {
                log.warn('AudioContext resume deferred (background tab)', {
                  error: String(err),
                });
              });
            }
          }

          await this.audio.play();
          if (this.blendActive) {
            this.audioSecondary.currentTime = this.audio.currentTime;
            await this.audioSecondary.play().catch(() => {});
          }
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
        if (this.blendActive) this.audioSecondary.pause();
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
      throw new TypeError(`Invalid seek position: ${seconds} (must be a finite number)`);
    }
    if (seconds < 0) {
      throw new Error(`Invalid seek position: ${seconds} (cannot be negative)`);
    }
    this.approachingEndFired = false;
    const duration = this.getDuration();
    const clampedSeconds = duration > 0 ? Math.min(seconds, duration) : seconds;
    this.audio.currentTime = clampedSeconds;
    if (this.blendActive) this.audioSecondary.currentTime = clampedSeconds;
    if (this._isPlaying) this.scheduleApproachingEndTimer();
    else this.clearApproachingEndTimer();
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

  setPlaybackRate(rate: number): void {
    this.ensureNotDisposed();
    const clamped = Math.max(0.5, Math.min(3, rate));
    this.audio.playbackRate = clamped;
    this.audio.preservesPitch = true;
  }

  setNormalizationGain(gainDb: number | null): void {
    this.normalizationFactor = gainDb === null ? 1 : Math.pow(10, gainDb / 20);
    if (this.inputBus && this.audioContext) {
      this.inputBus.gain.setValueAtTime(this.normalizationFactor, this.audioContext.currentTime);
    }
  }

  getCurrentTime(): number {
    return this.audio.currentTime;
  }

  getDuration(): number {
    if (this.stableDuration > 0) return this.stableDuration;
    const duration = this.audio.duration;
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

  onApproachingEnd(callback: () => void): () => void {
    this.approachingEndCallbacks.add(callback);
    return () => {
      this.approachingEndCallbacks.delete(callback);
    };
  }

  private scheduleApproachingEndTimer(): void {
    this.clearApproachingEndTimer();
    if (this.approachingEndFired) return;
    if (this.approachingEndCallbacks.size === 0) return;

    const duration = this.stableDuration > 0 ? this.stableDuration : this.audio.duration;
    if (!Number.isFinite(duration) || duration <= 0) return;

    const rate = this.audio.playbackRate > 0 ? this.audio.playbackRate : 1;
    const remainingMs =
      ((duration - this.audio.currentTime) * 1000) / rate - this.approachingEndThresholdMs;
    if (remainingMs <= 0) return;

    this.approachingEndTimer = setTimeout(() => {
      this.approachingEndTimer = null;
      if (this.approachingEndFired) return;
      this.approachingEndFired = true;
      for (const cb of this.approachingEndCallbacks) cb();
    }, remainingMs);
  }

  private clearApproachingEndTimer(): void {
    if (this.approachingEndTimer) {
      clearTimeout(this.approachingEndTimer);
      this.approachingEndTimer = null;
    }
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

    this.clearApproachingEndTimer();

    // Cancel pending load operations
    this.cancelCurrentLoad();

    // Clear preload state
    if (this.preloadReject) {
      this.preloadReject(new PreloadSupersededError('engine disposed'));
      this.preloadReject = null;
    }
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

    this.stopBlendDriftWatchdog();
    this.blendActive = false;

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
        } catch {
          // intentionally ignored
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

    const { promise, cancel } = this.createFadeOperation(
      fromLevel,
      toLevel,
      durationMs,
      true,
      volume => this.setVolume(volume)
    );

    this.currentFadeCancel = cancel;

    return { promise, cancel };
  }

  private createFadeOperation(
    fromLevel: number,
    toLevel: number,
    durationMs: number,
    useEasing: boolean,
    setVolume: (v: number) => void
  ): { promise: Promise<void>; cancel: () => void } {
    const fade = createFade(fromLevel, toLevel, durationMs, useEasing, setVolume);

    const cancel = () => {
      fade.cancel();
      if (this.currentFadeCancel === cancel) {
        this.currentFadeCancel = null;
      }
    };

    const promise = fade.promise.then(() => {
      if (this.currentFadeCancel === cancel) {
        this.currentFadeCancel = null;
      }
    });

    return { promise, cancel };
  }

  get analyser(): AnalyserNode | null {
    return this._analyser;
  }

  getAudioContext(): AudioContext | null {
    return this.ensureAudioContext();
  }

  getAudioElement(): HTMLAudioElement | null {
    return this.audio;
  }

  async enterVocalBlend(
    instrumentalUrl: string,
    vocalsUrl: string,
    positionSec: number,
    playing: boolean
  ): Promise<void> {
    this.ensureNotDisposed();

    const ctx = this.ensureAudioContext();
    if (!ctx || !this.webAudioInitialized || !this.elemGainPrimary || !this.elemGainSecondary) {
      throw new Error('WebAudio not available for vocal blend');
    }

    // Bypass the preload/swap machinery while co-playing two stems.
    this.cancelCurrentLoad();
    this.preloadedUrl = null;
    this.preloadPromise = null;

    this.blendActive = true;
    this.audioSecondary.muted = false;

    await Promise.all([
      this.loadElement(this.audio, instrumentalUrl),
      this.loadElement(this.audioSecondary, vocalsUrl),
    ]);

    const d = this.audio.duration;
    this.stableDuration = Number.isFinite(d) && d > 0 ? d : 0;

    const clamped =
      this.stableDuration > 0 ? Math.min(positionSec, this.stableDuration) : positionSec;
    this.audio.currentTime = clamped;
    this.audioSecondary.currentTime = clamped;

    this.elemGainPrimary.gain.setValueAtTime(1, ctx.currentTime);
    this.applyVocalBlendGain(this.vocalBlendLevel);

    this.approachingEndFired = false;

    if (playing) {
      await this.play();
    }

    this.startBlendDriftWatchdog();
    log.info('Vocal blend co-play started', { positionSec: clamped, playing });
  }

  setVocalBlend(level: number): void {
    this.ensureNotDisposed();
    const v = Number(level);
    this.vocalBlendLevel = Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    if (this.blendActive) this.applyVocalBlendGain(this.vocalBlendLevel);
  }

  async exitVocalBlend(resumeUrl: string, positionSec: number, playing: boolean): Promise<void> {
    this.ensureNotDisposed();

    // load() also tears down the blend (idempotent), so this call is the explicit intent and the
    // load-time teardown is a defensive no-op.
    this.teardownVocalBlend();

    await this.load(resumeUrl);
    this.seek(positionSec);
    if (playing) await this.play();
    log.info('Vocal blend co-play stopped', { positionSec, playing });
  }

  private teardownVocalBlend(): void {
    if (!this.blendActive) return;

    this.stopBlendDriftWatchdog();
    this.blendActive = false;

    if (this.elemGainSecondary && this.audioContext) {
      this.elemGainSecondary.gain.setTargetAtTime(
        0,
        this.audioContext.currentTime,
        VOCAL_BLEND_GAIN_SMOOTHING_SEC
      );
    }
    this.audioSecondary.pause();
    this.audioSecondary.src = '';
    this.audioSecondary.load();
    this.audioSecondary.muted = true;
  }

  isVocalBlendActive(): boolean {
    return this.blendActive;
  }

  private applyVocalBlendGain(level: number): void {
    if (!this.elemGainSecondary || !this.audioContext) return;
    this.elemGainSecondary.gain.setTargetAtTime(
      level,
      this.audioContext.currentTime,
      VOCAL_BLEND_GAIN_SMOOTHING_SEC
    );
  }

  private async loadElement(element: HTMLAudioElement, url: string): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const handleCanPlay = () => {
        cleanup();
        resolve();
      };
      const handleError = () => {
        cleanup();
        const message = element.error?.message ?? 'Unknown error';
        reject(new Error(`Failed to load stem: ${this.sanitizeError(message)}`));
      };
      const timeoutId = setTimeout(() => {
        cleanup();
        reject(new Error(`Stem load timeout after ${this.loadTimeoutMs / 1000} seconds`));
      }, this.loadTimeoutMs);
      const cleanup = () => {
        clearTimeout(timeoutId);
        element.removeEventListener('canplay', handleCanPlay);
        element.removeEventListener('error', handleError);
      };
      element.addEventListener('canplay', handleCanPlay, { once: true });
      element.addEventListener('error', handleError, { once: true });
      element.src = url;
      element.load();
    });
  }

  private startBlendDriftWatchdog(): void {
    this.stopBlendDriftWatchdog();
    this.blendDriftWatchdog = setInterval(() => {
      if (!this.blendActive) return;
      // Skip while the instrumental (primary) element is not advancing — correcting drift
      // against a paused/stalled element would repeatedly snap the vocals element backward.
      if (this.audio.paused || this.audio.readyState < HTMLMediaElement.HAVE_FUTURE_DATA) return;
      const drift = Math.abs(this.audio.currentTime - this.audioSecondary.currentTime);
      if (drift > VOCAL_BLEND_DRIFT_THRESHOLD_SEC) {
        this.audioSecondary.currentTime = this.audio.currentTime;
      }
    }, 250);
  }

  private stopBlendDriftWatchdog(): void {
    if (this.blendDriftWatchdog) {
      clearInterval(this.blendDriftWatchdog);
      this.blendDriftWatchdog = null;
    }
  }

  async preload(url: string): Promise<void> {
    this.ensureNotDisposed();

    // Already preloaded this URL
    if (this.preloadedUrl === url && this.preloadPromise) {
      return this.preloadPromise;
    }

    // Cancel any existing preload operation to prevent race condition. The superseded
    // promise must settle, otherwise its awaiters hang until an external timeout.
    if (this.preloadEventCleanup) {
      this.preloadEventCleanup();
      this.preloadEventCleanup = null;
    }
    if (this.preloadReject) {
      this.preloadReject(new PreloadSupersededError('new preload requested'));
      this.preloadReject = null;
    }

    // Always preload into secondary element (this.audio is always the active one)
    const targetElement = this.audioSecondary;

    this.preloadedUrl = url;
    this.preloadPromise = new Promise<void>((resolve, reject) => {
      this.preloadReject = reject;

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
        this.preloadReject = null;
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

    const playbackRate = oldElement.playbackRate;
    const preservesPitch = oldElement.preservesPitch;

    oldElement.pause();
    oldElement.src = '';
    oldElement.load();

    newElement.playbackRate = playbackRate;
    newElement.preservesPitch = preservesPitch;

    this.approachingEndFired = false;
    this.clearApproachingEndTimer();

    this.audio = newElement;
    this.audioSecondary = oldElement;

    const d = this.audio.duration;
    this.stableDuration = Number.isFinite(d) && d > 0 ? d : 0;

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

    // New secondary starts muted so next play() won't be blocked by Chrome's
    // "video-only background media" power-saving policy.
    this.audioSecondary.muted = true;

    this.preloadedUrl = null;
    this.preloadPromise = null;

    if (!this.audio.paused) {
      this._isPlaying = true;
      this.scheduleApproachingEndTimer();
    }
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
    // Keep secondary muted during play() — Chrome blocks play() on volume=0 elements
    // as "video-only background media". muted=true is not subject to this policy.
    nextElement.muted = true;

    if (this.audioContext) {
      const ctxState = this.audioContext.state as string;
      if (ctxState === 'suspended' || ctxState === 'interrupted') {
        this.audioContext.resume().catch(err => {
          log.warn('AudioContext resume deferred during seamless switch', {
            error: String(err),
          });
        });
      }
    }

    // Start muted playback to prime the decode pipeline.
    // Secondary gain node is already at 0 — no audible output yet.
    await nextElement.play();
    // Unmute immediately after play() — muted=true would silence the MediaElementAudioSourceNode.
    // Chrome's power-saving check only runs at play() call time, not after.
    nextElement.muted = false;

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

    nextElement.muted = false;

    if (!this.webAudioInitialized) {
      nextElement.volume = this.storedVolume;
    }

    if (this.audioContext) {
      const state = this.audioContext.state as string;
      if (state === 'suspended' || state === 'interrupted') {
        this.audioContext.resume().catch(err => {
          log.warn('AudioContext resume deferred during transition', {
            error: String(err),
          });
        });
      }
    }

    await nextElement.play();

    this.performElementSwap();

    if (this.webAudioInitialized && this.audioContext) {
      if (this.elemGainPrimary) {
        this.elemGainPrimary.gain.setValueAtTime(1, this.audioContext.currentTime);
      }
      if (this.elemGainSecondary) {
        this.elemGainSecondary.gain.setValueAtTime(0, this.audioContext.currentTime);
      }
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
    const { promise, cancel } = this.createFadeOperation(
      fromLevel,
      toLevel,
      durationMs,
      false,
      v => {
        element.volume = v;
      }
    );
    this.activeElementFades.add(cancel);
    try {
      await promise;
    } finally {
      this.activeElementFades.delete(cancel);
    }
  }
}
