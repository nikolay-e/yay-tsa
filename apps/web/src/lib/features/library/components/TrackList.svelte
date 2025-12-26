<script lang="ts">
  import { derived } from 'svelte/store';
  import { tick } from 'svelte';
  import { ticksToSeconds, type AudioItem } from '@yaytsa/core';
  import { player, currentTrack } from '../../player/stores/player.store.js';
  import { formatDuration } from '../../../shared/utils/time.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';

  export let tracks: AudioItem[] = [];
  export let showAlbum: boolean = false;

  const currentTrackId = derived(currentTrack, $track => $track?.Id ?? null);

  const trackElements: Map<string, HTMLButtonElement> = new Map();
  let previousTrackId: string | null = null;

  $: if ($currentTrackId && $currentTrackId !== previousTrackId) {
    previousTrackId = $currentTrackId;
    tick().then(() => scrollToCurrentTrack($currentTrackId));
  }

  function scrollToCurrentTrack(trackId: string | null): void {
    if (!trackId) return;
    const element = trackElements.get(trackId);
    if (!element) return;
    setTimeout(() => {
      element.scrollIntoView({
        behavior: 'smooth',
        block: 'nearest',
        inline: 'nearest',
      });
    }, 50);
  }

  function setTrackRef(node: HTMLButtonElement, trackId: string) {
    trackElements.set(trackId, node);
    return {
      destroy() {
        trackElements.delete(trackId);
      },
    };
  }

  function playTrack(_track: AudioItem, index: number) {
    hapticSelect();
    player.playFromAlbum(tracks, index);
  }
</script>

<div class="track-list-container">
  {#if tracks.length > 0}
    <div class="track-list">
      <div class="track-list-row header">
        <div class="track-number">#</div>
        <div class="track-title">Title</div>
        {#if showAlbum}
          <div class="track-album">Album</div>
        {/if}
        <div class="track-duration">Duration</div>
      </div>

      {#each tracks as track, index (track.Id)}
        <button
          type="button"
          class="track-list-row track"
          data-testid="track-row"
          class:playing={$currentTrackId === track.Id}
          use:setTrackRef={track.Id}
          on:click={() => playTrack(track, index)}
          aria-label="Play {track.Name}{track.Artists && track.Artists.length > 0 ? ' by ' + track.Artists.join(', ') : ''}"
        >
          <div class="track-number">
            {#if $currentTrackId === track.Id}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
              </svg>
            {:else}
              {track.IndexNumber || index + 1}
            {/if}
          </div>
          <div class="track-title">
            <div class="track-name" data-testid="track-title">{track.Name}</div>
            {#if track.Artists && track.Artists.length > 0}
              <div class="track-artist">{track.Artists?.join(', ')}</div>
            {/if}
          </div>
          {#if showAlbum}
            <div class="track-album">{track.Album || '-'}</div>
          {/if}
          <div class="track-duration">
            {formatDuration(ticksToSeconds(track.RunTimeTicks || 0))}
          </div>
        </button>
      {/each}
    </div>
  {:else}
    <div class="empty">No tracks available</div>
  {/if}
</div>

<style>
  .track-list-container {
    width: 100%;
  }

  .track-list {
    display: flex;
    flex-direction: column;
  }

  .track-list-row {
    display: grid;
    grid-template-columns: 40px 1fr 80px;
    gap: var(--spacing-md);
    align-items: center;
    padding: var(--spacing-sm) var(--spacing-md);
    border-radius: var(--radius-sm);
    text-align: left;
  }

  .track-list-row.header {
    color: var(--color-text-tertiary);
    font-size: 0.75rem;
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    border-bottom: 1px solid var(--color-border);
    background: none;
    cursor: default;
  }

  .track-list-row.track {
    background: none;
    border: none;
    color: inherit;
    cursor: pointer;
    transition: background-color 0.2s;
    width: 100%;
    /* Eliminate 300ms click delay on mobile */
    touch-action: manipulation;
  }

  .track-list-row.track:hover {
    background-color: var(--color-bg-hover);
  }

  .track-list-row.track.playing {
    background-color: var(--color-bg-tertiary);
    color: var(--color-accent);
  }

  .track-number {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--color-text-secondary);
    font-size: 0.875rem;
  }

  .track-list-row.playing .track-number {
    color: var(--color-accent);
  }

  .track-title {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .track-name {
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .track-artist {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .track-album {
    color: var(--color-text-secondary);
    font-size: 0.875rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .track-duration {
    color: var(--color-text-secondary);
    font-size: 0.875rem;
    text-align: right;
  }

  .empty {
    padding: var(--spacing-xl);
    text-align: center;
    color: var(--color-text-secondary);
  }

  @media (max-width: 768px) {
    .track-list-row {
      grid-template-columns: 40px 1fr 60px;
      gap: var(--spacing-sm);
      padding: var(--spacing-md); /* Increase padding for 44px min-height */
      min-height: 44px; /* Touch target minimum */
    }

    /* Keep artist visible on mobile - important for compilations */
    .track-artist {
      font-size: 0.8125rem; /* Slightly smaller but still readable */
    }

    .track-number {
      font-size: 0.8125rem;
    }

    .track-duration {
      font-size: 0.8125rem;
    }
  }
</style>
