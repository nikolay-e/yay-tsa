<script lang="ts">
  import type { AudioItem } from '@jellyfin-mini/core';
  import { player, currentTrack } from '../../stores/player.js';
  import { formatDuration } from '../../utils/time.js';

  export let tracks: AudioItem[] = [];
  export let showAlbum: boolean = false;

  function playTrack(_track: AudioItem, index: number) {
    // Play the entire album starting from the clicked track
    // This allows continuous playback through the rest of the album
    player.playFromAlbum(tracks, index);
  }

  function playAll() {
    if (tracks.length > 0) {
      player.playAlbum(tracks);
    }
  }

  function isCurrentTrack(track: AudioItem): boolean {
    return $currentTrack?.Id === track.Id;
  }
</script>

<div class="track-list-container">
  {#if tracks.length > 0}
    <div class="track-list-header">
      <button type="button" class="play-all-btn" on:click={playAll}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
          <path d="M8 5v14l11-7z" />
        </svg>
        Play All
      </button>
    </div>

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
          class:playing={isCurrentTrack(track)}
          on:click={() => playTrack(track, index)}
        >
          <div class="track-number">
            {#if isCurrentTrack(track)}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
              </svg>
            {:else}
              {track.IndexNumber || index + 1}
            {/if}
          </div>
          <div class="track-title">
            <div class="track-name">{track.Name}</div>
            {#if track.Artists && track.Artists.length > 0}
              <div class="track-artist">{track.Artists?.join(', ')}</div>
            {/if}
          </div>
          {#if showAlbum}
            <div class="track-album">{track.Album || '-'}</div>
          {/if}
          <div class="track-duration">
            {formatDuration((track.RunTimeTicks || 0) / 10000000)}
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

  .track-list-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--spacing-md) 0;
    margin-bottom: var(--spacing-md);
  }

  .play-all-btn {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-lg);
    background-color: var(--color-accent);
    color: white;
    border: none;
    border-radius: var(--radius-lg);
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
  }

  .play-all-btn:hover {
    background-color: var(--color-accent-hover);
    transform: scale(1.05);
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
      grid-template-columns: 32px 1fr 60px;
      gap: var(--spacing-sm);
      padding: var(--spacing-sm);
    }

    .track-artist {
      display: none;
    }
  }
</style>
