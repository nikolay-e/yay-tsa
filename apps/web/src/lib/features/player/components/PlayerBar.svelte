<script lang="ts">
  import { currentTrack, player } from '../stores/player.store.js';
  import { currentTime, duration } from '../stores/player.store.js';
  import { getAlbumArtUrl } from '../../../shared/utils/image.js';
  import SeekBar from './SeekBar.svelte';
  import Controls from './Controls.svelte';
  import QueuePanel from './QueuePanel.svelte';
  import SleepTimerModal from './SleepTimerModal.svelte';
  import { PLAYER_TEST_IDS } from '$lib/shared/test-ids';

  let showQueue = false;
  let showSleepTimer = false;

  function handleSeek(event: CustomEvent<number> | Event) {
    const detail = (event as CustomEvent<number>).detail;
    if (detail !== undefined) {
      player.seek(detail);
    }
  }

  function toggleQueue() {
    showQueue = !showQueue;
  }

  function toggleSleepTimer() {
    showSleepTimer = !showSleepTimer;
  }
</script>

{#if $currentTrack}
  <div class="player-bar" data-testid="player-bar">
    <div class="player-content">
      <!-- Now Playing Info -->
      <div class="now-playing">
        {#if $currentTrack.AlbumId}
          <img
            src={getAlbumArtUrl($currentTrack.AlbumId, 'small')}
            alt={$currentTrack.Album || 'Album art'}
            class="album-art"
            width="56"
            height="56"
          />
        {:else}
          <div class="album-art placeholder">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
            </svg>
          </div>
        {/if}

        <div class="track-info">
          <div class="track-name" data-testid={PLAYER_TEST_IDS.CURRENT_TRACK_TITLE}>{$currentTrack.Name}</div>
          <div class="track-artist" data-testid={PLAYER_TEST_IDS.CURRENT_TRACK_ARTIST}>
            {$currentTrack.Artists?.join(', ') || 'Unknown Artist'}
          </div>
        </div>
      </div>

      <!-- Playback Controls (centered) -->
      <div class="controls-section">
        <Controls
          {showQueue}
          on:toggleSleepTimer={toggleSleepTimer}
          on:toggleQueue={toggleQueue}
        />
        <SeekBar
          {currentTime}
          {duration}
          on:seek={handleSeek}
        />
      </div>

      <!-- Spacer for centering -->
      <div class="spacer"></div>
    </div>
  </div>

  <QueuePanel isOpen={showQueue} on:close={() => (showQueue = false)} />
  <SleepTimerModal isOpen={showSleepTimer} on:close={() => (showSleepTimer = false)} />
{/if}

<style>
  .player-bar {
    position: fixed;
    bottom: 0;
    left: 240px;
    right: 0;
    height: auto;
    min-height: 72px;
    background-color: var(--color-bg-secondary);
    border-top: 1px solid var(--color-border);
    z-index: 100;
    box-shadow: var(--shadow-lg);
    /* iOS safe area support - extend background behind home indicator */
    padding-bottom: var(--safe-area-inset-bottom);
  }

  .player-content {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
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

  .spacer {
    /* Empty spacer for grid centering */
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
    width: 500px;
    min-width: 300px;
    max-width: 600px;
  }

  @media (max-width: 768px) {
    .player-bar {
      left: 0;
      height: auto;
      min-height: 64px;
      /* Position flush above bottom tab bar (52px tabs + safe area) */
      bottom: calc(52px + var(--safe-area-inset-bottom));
      /* Keep internal padding for content spacing */
      padding-bottom: var(--spacing-sm);
    }

    .album-art {
      width: 48px;
      height: 48px;
    }

    .player-content {
      grid-template-columns: 1fr;
      grid-template-rows: auto auto;
      padding: var(--spacing-sm);
      gap: var(--spacing-sm);
    }

    .now-playing {
      grid-column: 1;
    }

    .controls-section {
      grid-column: 1;
      width: 100%;
      min-width: unset;
      max-width: unset;
    }

    .spacer {
      display: none;
    }
  }
</style>
