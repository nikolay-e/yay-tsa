/**
 * Media Session API implementation
 * Enables background playback and lock screen controls
 */

import { createLogger } from '@yay-tsa/core';

const log = createLogger('MediaSession');

export interface TrackMetadata {
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
  private readonly isSupported: boolean;

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
  public updateMetadata(metadata: TrackMetadata): void {
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
   * setActionHandler that never throws — some browsers (notably older iOS Safari)
   * reject actions they don't implement.
   */
  private setHandler(action: MediaSessionAction, handler: MediaSessionActionHandler | null): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch (error) {
      log.debug('Action handler not supported', { action, error: String(error) });
    }
  }

  /**
   * Set up the stable action handlers (play/pause/seek/stop). Next/previous are managed
   * separately via setNavigationHandlers so they can be enabled/disabled as the queue changes.
   * A missing handler is explicitly cleared (set to null) rather than left stale.
   */
  public setActionHandlers(handlers: MediaSessionHandlers): void {
    if (!this.isSupported || !navigator.mediaSession) {
      return;
    }

    this.setHandler('play', handlers.onPlay ?? null);
    this.setHandler('pause', handlers.onPause ?? null);
    this.setNavigationHandlers(handlers.onNext ?? null, handlers.onPrevious ?? null);

    this.setHandler(
      'seekto',
      handlers.onSeek
        ? details => {
            if (details.seekTime !== undefined && handlers.onSeek) {
              handlers.onSeek(details.seekTime);
            }
          }
        : null
    );

    this.setHandler('stop', () => {
      this.clearMetadata();
      this.setPlaybackState('none');
    });

    // Deliberately do NOT register seekforward/seekbackward: on iOS those render as ±10/15s skip
    // buttons on the lock screen that crowd out (or replace) next/previous track. Clear them so a
    // stale handler can never shadow the track controls.
    this.setHandler('seekforward', null);
    this.setHandler('seekbackward', null);
  }

  /**
   * Enable or disable the lock-screen next/previous track controls. Pass null to remove a control
   * (e.g. no next track at the end of the queue) instead of leaving a dead button.
   */
  public setNavigationHandlers(onNext: (() => void) | null, onPrevious: (() => void) | null): void {
    this.setHandler('nexttrack', onNext);
    this.setHandler('previoustrack', onPrevious);
  }

  /**
   * Update position state (for seek bar on lock screen)
   */
  public updatePositionState(duration: number, position: number, playbackRate: number = 1): void {
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
      'seekforward',
      'seekbackward',
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
