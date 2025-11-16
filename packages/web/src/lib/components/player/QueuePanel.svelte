<script lang="ts">
  import { player, queueItems, currentTrack } from '../../stores/player.js';
  import { getAlbumArtUrl } from '../../utils/image.js';
  import { createEventDispatcher } from 'svelte';
  import type { AudioItem } from '@yaytsa/core';

  export let isOpen = false;

  const dispatch = createEventDispatcher<{ close: void }>();

  function handleClose() {
    dispatch('close');
  }

  function handleBackdropClick(event: MouseEvent) {
    if (event.target === event.currentTarget) {
      handleClose();
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      handleClose();
    }
  }

  async function playTrack(_track: AudioItem, index: number) {
    const tracks = $queueItems;
    if (tracks.length > 0) {
      await player.playFromAlbum(tracks, index);
    }
  }

  function removeTrack(index: number) {
    player.removeFromQueue(index);
  }

  function clearAll() {
    player.clearQueue();
    handleClose();
  }

  function formatDuration(ticks: number | undefined): string {
    if (!ticks) return '0:00';
    const seconds = Math.floor(ticks / 10_000_000);
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }
</script>

<svelte:window on:keydown={handleKeydown} />

{#if isOpen}
  <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
  <div class="queue-overlay" on:click={handleBackdropClick}>
    <div class="queue-panel" role="dialog" aria-modal="true" aria-label="Playback Queue">
      <header class="queue-header">
        <h2>Queue</h2>
        <div class="header-actions">
          {#if $queueItems.length > 0}
            <button type="button" class="clear-btn" on:click={clearAll}>Clear</button>
          {/if}
          <button type="button" class="close-btn" on:click={handleClose} aria-label="Close queue">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
            </svg>
          </button>
        </div>
      </header>

      <div class="queue-content">
        {#if $queueItems.length === 0}
          <div class="empty-state">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="currentColor">
              <path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9h-4v4h-2v-4H9V9h4V5h2v4h4v2z" />
            </svg>
            <p>Queue is empty</p>
          </div>
        {:else}
          <ul class="queue-list">
            {#each $queueItems as track, index (track.Id + '-' + index)}
              {@const isCurrent = $currentTrack?.Id === track.Id}
              <li class="queue-item" class:current={isCurrent}>
                <button type="button" class="track-button" on:click={() => playTrack(track, index)}>
                  <div class="track-number">
                    {#if isCurrent}
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
                      </svg>
                    {:else}
                      {index + 1}
                    {/if}
                  </div>

                  {#if track.AlbumId}
                    <img
                      src={getAlbumArtUrl(track.AlbumId, 'small')}
                      alt=""
                      class="track-art"
                      loading="lazy"
                    />
                  {:else}
                    <div class="track-art placeholder">
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
                      </svg>
                    </div>
                  {/if}

                  <div class="track-info">
                    <span class="track-name">{track.Name}</span>
                    <span class="track-artist">{track.Artists?.join(', ') || 'Unknown Artist'}</span>
                  </div>

                  <span class="track-duration">{formatDuration(track.RunTimeTicks)}</span>
                </button>

                <button
                  type="button"
                  class="remove-btn"
                  on:click|stopPropagation={() => removeTrack(index)}
                  aria-label="Remove from queue"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
                  </svg>
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .queue-overlay {
    position: fixed;
    inset: 0;
    background-color: rgba(0, 0, 0, 0.5);
    z-index: 200;
    display: flex;
    justify-content: flex-end;
  }

  .queue-panel {
    width: 400px;
    max-width: 100%;
    height: 100%;
    background-color: var(--color-bg-primary);
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-xl);
  }

  .queue-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: var(--spacing-lg);
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .queue-header h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
  }

  .clear-btn {
    padding: var(--spacing-xs) var(--spacing-sm);
    background: none;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    color: var(--color-text-secondary);
    font-size: 0.875rem;
    cursor: pointer;
  }

  .clear-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-text-primary);
  }

  .close-btn {
    padding: var(--spacing-xs);
    background: none;
    border: none;
    color: var(--color-text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
  }

  .close-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-text-primary);
  }

  .queue-content {
    flex: 1;
    overflow-y: auto;
    padding: var(--spacing-md);
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--color-text-tertiary);
    gap: var(--spacing-md);
  }

  .queue-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
  }

  .queue-item {
    display: flex;
    align-items: center;
    gap: var(--spacing-xs);
    border-radius: var(--radius-sm);
    transition: background-color 0.15s;
  }

  .queue-item:hover {
    background-color: var(--color-bg-hover);
  }

  .queue-item.current {
    background-color: var(--color-bg-tertiary);
  }

  .track-button {
    flex: 1;
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm);
    background: none;
    border: none;
    color: inherit;
    text-align: left;
    cursor: pointer;
    min-width: 0;
  }

  .track-number {
    width: 24px;
    text-align: center;
    color: var(--color-text-tertiary);
    font-size: 0.875rem;
    flex-shrink: 0;
  }

  .queue-item.current .track-number {
    color: var(--color-accent);
  }

  .track-art {
    width: 40px;
    height: 40px;
    border-radius: var(--radius-xs);
    object-fit: cover;
    flex-shrink: 0;
  }

  .track-art.placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: var(--color-bg-tertiary);
    color: var(--color-text-tertiary);
  }

  .track-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .track-name {
    font-size: 0.9375rem;
    font-weight: 500;
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .queue-item.current .track-name {
    color: var(--color-accent);
  }

  .track-artist {
    font-size: 0.8125rem;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .track-duration {
    font-size: 0.8125rem;
    color: var(--color-text-tertiary);
    flex-shrink: 0;
  }

  .remove-btn {
    padding: var(--spacing-xs);
    background: none;
    border: none;
    color: var(--color-text-tertiary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    opacity: 0;
    transition: opacity 0.15s, background-color 0.15s;
  }

  .queue-item:hover .remove-btn {
    opacity: 1;
  }

  .remove-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-error);
  }

  @media (max-width: 768px) {
    .queue-overlay {
      align-items: flex-end;
      justify-content: center;
    }

    .queue-panel {
      width: 100%;
      height: 70vh;
      border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    }

    .remove-btn {
      opacity: 1;
    }
  }
</style>
