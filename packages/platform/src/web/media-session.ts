/**
 * Media Session API implementation
 * Enables background playback and lock screen controls
 */

import { createLogger } from '@yaytsa/core';

const log = createLogger('MediaSession');

export interface MediaMetadata {
  title: string;
  artist: string;
  album: string;
  artwork?: string;
}

export interface MediaSessionHandlers {
  onPlay?: () => void;
  onPause?: () => void;
  onNext?: () => void;
  onPrevious?: () => void;
  onSeek?: (seconds: number) => void;
}

export class MediaSessionManager {
  private isSupported: boolean;

  constructor() {
    this.isSupported = typeof navigator !== 'undefined' && 'mediaSession' in navigator;
    log.debug('Media Session API supported', { isSupported: this.isSupported });
  }

  /**
   * Check if Media Session API is supported
   */
  public supported(): boolean {
    return this.isSupported;
  }

  /**
   * Update media metadata (shown on lock screen, notifications, etc.)
   */
  public updateMetadata(metadata: MediaMetadata): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    const artwork = metadata.artwork
      ? [
          { src: metadata.artwork, sizes: '96x96', type: 'image/png' },
          { src: metadata.artwork, sizes: '128x128', type: 'image/png' },
          { src: metadata.artwork, sizes: '192x192', type: 'image/png' },
          { src: metadata.artwork, sizes: '256x256', type: 'image/png' },
          { src: metadata.artwork, sizes: '384x384', type: 'image/png' },
          { src: metadata.artwork, sizes: '512x512', type: 'image/png' },
        ]
      : [];

    navigator.mediaSession.metadata = new MediaMetadata({
      title: metadata.title,
      artist: metadata.artist,
      album: metadata.album,
      artwork,
    });
  }

  /**
   * Clear media metadata
   */
  public clearMetadata(): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    navigator.mediaSession.metadata = null;
  }

  /**
   * Set playback state (playing, paused, none)
   */
  public setPlaybackState(state: 'none' | 'paused' | 'playing'): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    navigator.mediaSession.playbackState = state;
  }

  /**
   * Set up action handlers for media controls
   */
  public setActionHandlers(handlers: MediaSessionHandlers): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    // Play action
    if (handlers.onPlay) {
      navigator.mediaSession.setActionHandler('play', handlers.onPlay);
    }

    // Pause action
    if (handlers.onPause) {
      navigator.mediaSession.setActionHandler('pause', handlers.onPause);
    }

    // Next track action
    if (handlers.onNext) {
      navigator.mediaSession.setActionHandler('nexttrack', handlers.onNext);
    }

    // Previous track action
    if (handlers.onPrevious) {
      navigator.mediaSession.setActionHandler('previoustrack', handlers.onPrevious);
    }

    // Seek action
    if (handlers.onSeek) {
      navigator.mediaSession.setActionHandler('seekto', details => {
        if (details.seekTime !== undefined && handlers.onSeek) {
          handlers.onSeek(details.seekTime);
        }
      });
    }

    // Stop action (clear playback)
    navigator.mediaSession.setActionHandler('stop', () => {
      this.clearMetadata();
      this.setPlaybackState('none');
    });
  }

  /**
   * Update position state (for seek bar on lock screen)
   */
  public updatePositionState(duration: number, position: number, playbackRate: number = 1.0): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    // Only set position state if we have valid values
    if (duration > 0 && position >= 0 && position <= duration) {
      try {
        navigator.mediaSession.setPositionState({
          duration,
          playbackRate,
          position,
        });
      } catch (error) {
        // Position state errors can occur during rapid seeking and are non-critical
        log.debug('Failed to set position state', { error: String(error), duration, position });
      }
    }
  }

  /**
   * Clear all action handlers
   */
  public clearActionHandlers(): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    const actions: MediaSessionAction[] = [
      'play',
      'pause',
      'previoustrack',
      'nexttrack',
      'seekto',
      'stop',
    ];

    actions.forEach(action => {
      try {
        navigator.mediaSession.setActionHandler(action, null);
      } catch (error) {
        // Some actions might not be supported on all browsers
        log.debug('Failed to clear action handler', { action, error: String(error) });
      }
    });
  }

  /**
   * Reset media session (clear metadata and handlers)
   */
  public reset(): void {
    this.clearMetadata();
    this.setPlaybackState('none');
    this.clearActionHandlers();
  }
}
