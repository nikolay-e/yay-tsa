<script lang="ts">
  import { currentTrack, currentTime, duration, player } from '../../stores/player.js';
  import { getAlbumArtUrl } from '../../utils/image.js';
  import SeekBar from './SeekBar.svelte';
  import Controls from './Controls.svelte';

  function handleSeek(event: CustomEvent<number> | Event) {
    // Handle both CustomEvent from Svelte dispatcher and regular Events
    const detail = (event as CustomEvent<number>).detail;
    if (detail !== undefined) {
      player.seek(detail);
    }
  }
</script>

{#if $currentTrack}
  <div class="player-bar">
    <div class="player-content">
      <!-- Now Playing Info -->
      <div class="now-playing">
        {#if $currentTrack.AlbumId}
          <img
            src={getAlbumArtUrl($currentTrack.AlbumId, 'small')}
            alt={$currentTrack.Album || 'Album art'}
            class="album-art"
          />
        {:else}
          <div class="album-art placeholder">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
            </svg>
          </div>
        {/if}

        <div class="track-info">
          <div class="track-name">{$currentTrack.Name}</div>
          <div class="track-artist">
            {$currentTrack.Artists?.join(', ') || 'Unknown Artist'}
          </div>
        </div>
      </div>

      <!-- Playback Controls -->
      <div class="controls-section">
        <Controls />
        <SeekBar
          currentTime={$currentTime}
          duration={$duration}
          on:seek={handleSeek}
        />
      </div>

      <!-- Extra Controls (placeholder for queue, etc.) -->
      <div class="extra-controls">
        <!-- Future: Queue button, lyrics, etc. -->
      </div>
    </div>
  </div>
{/if}

<style>
  .player-bar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 90px;
    background-color: var(--color-bg-secondary);
    border-top: 1px solid var(--color-border);
    z-index: 100;
    box-shadow: var(--shadow-lg);
    /* iOS safe area support - extend background behind home indicator */
    padding-bottom: var(--safe-area-inset-bottom);
  }

  .player-content {
    display: grid;
    grid-template-columns: 1fr 2fr 1fr;
    align-items: center;
    gap: var(--spacing-lg);
    height: 100%;
    padding: 0 var(--spacing-lg);
    /* iOS safe area support - horizontal padding */
    padding-left: calc(var(--spacing-lg) + var(--safe-area-inset-left));
    padding-right: calc(var(--spacing-lg) + var(--safe-area-inset-right));
    max-width: 1600px;
    margin: 0 auto;
  }

  .now-playing {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    min-width: 0;
  }

  .album-art {
    width: 56px;
    height: 56px;
    border-radius: var(--radius-sm);
    object-fit: cover;
    flex-shrink: 0;
  }

  .album-art.placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: var(--color-bg-tertiary);
    color: var(--color-text-tertiary);
  }

  .track-info {
    min-width: 0;
    flex: 1;
  }

  .track-name {
    font-weight: 500;
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

  .controls-section {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
    max-width: 600px;
    margin: 0 auto;
    width: 100%;
  }

  .extra-controls {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: var(--spacing-sm);
  }

  /* Responsive adjustments */
  @media (max-width: 768px) {
    .player-bar {
      height: auto;
      min-height: 90px;
      /* Position above bottom tab bar (56px) */
      bottom: calc(56px + var(--safe-area-inset-bottom));
      padding-bottom: 0; /* Reset padding-bottom, safe area handled in bottom position */
    }

    .player-content {
      grid-template-columns: 1fr;
      grid-template-rows: auto auto;
      padding: var(--spacing-md);
      gap: var(--spacing-md);
    }

    .extra-controls {
      display: none;
    }

    .controls-section {
      order: 2;
    }
  }
</style>
