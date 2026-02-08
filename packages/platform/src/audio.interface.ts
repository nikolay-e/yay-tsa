/**
 * Platform-agnostic audio engine interface
 * Abstracts audio playback across web, desktop, and mobile platforms
 */

export interface AudioEngine {
  /**
   * Load an audio source URL
   * @param url The audio stream URL
   */
  load(url: string): Promise<void>;

  /**
   * Start or resume playback
   */
  play(): Promise<void>;

  /**
   * Pause playback
   */
  pause(): void;

  /**
   * Seek to a specific position
   * @param seconds Position in seconds
   */
  seek(seconds: number): void;

  /**
   * Set volume level
   * @param level Volume from 0.0 to 1.0
   */
  setVolume(level: number): void;

  /**
   * Get current playback position
   * @returns Current time in seconds
   */
  getCurrentTime(): number;

  /**
   * Get total duration
   * @returns Duration in seconds
   */
  getDuration(): number;

  /**
   * Get current volume
   * @returns Volume level from 0.0 to 1.0
   */
  getVolume(): number;

  /**
   * Check if audio is currently playing
   */
  isPlaying(): boolean;

  /**
   * Subscribe to time updates during playback
   * @param callback Called periodically with current time
   * @returns Cleanup function to unsubscribe
   */
  onTimeUpdate(callback: (seconds: number) => void): () => void;

  /**
   * Subscribe to playback end event
   * @param callback Called when track finishes
   * @returns Cleanup function to unsubscribe
   */
  onEnded(callback: () => void): () => void;

  /**
   * Subscribe to error events
   * @param callback Called when an error occurs
   * @returns Cleanup function to unsubscribe
   */
  onError(callback: (error: Error) => void): () => void;

  /**
   * Subscribe to loading/buffering state changes
   * @param callback Called when loading state changes
   * @returns Cleanup function to unsubscribe
   */
  onLoading(callback: (isLoading: boolean) => void): () => void;

  /**
   * Clean up resources
   */
  dispose(): void;

  preload?(url: string): Promise<void>;

  isPreloaded?(url: string): boolean;

  seamlessSwitch?(
    seekPosition?: number,
    crossfadeDurationMs?: number
  ): Promise<{ duration: number }>;

  /**
   * Gradually fade volume over time (optional - not all engines support this)
   * @param fromLevel Starting volume (0.0 to 1.0)
   * @param toLevel Target volume (0.0 to 1.0)
   * @param durationMs Duration of fade in milliseconds
   * @returns Promise that resolves when fade completes, cleanup function to cancel
   */
  fadeVolume?(
    fromLevel: number,
    toLevel: number,
    durationMs: number
  ): { promise: Promise<void>; cancel: () => void };

  /**
   * Get the underlying AudioContext if available (for advanced audio features)
   */
  getAudioContext?(): AudioContext | null;

  /**
   * Get the underlying HTMLAudioElement if available (for Web Audio processing)
   */
  getAudioElement?(): HTMLAudioElement | null;

  /**
   * Enable or disable karaoke mode (vocal removal via phase cancellation)
   */
  setKaraokeMode?(enabled: boolean): void;

  /**
   * Check if karaoke mode is currently enabled
   */
  isKaraokeModeEnabled?(): boolean;
}
