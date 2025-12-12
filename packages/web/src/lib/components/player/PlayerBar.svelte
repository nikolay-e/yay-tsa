<script lang="ts">
  import { currentTrack, player, queueItems } from '../../stores/player.js';
  import { currentTime, duration } from '../../stores/player.js';
  import { getAlbumArtUrl } from '../../utils/image.js';
  import SeekBar from './SeekBar.svelte';
  import Controls from './Controls.svelte';
  import QueuePanel from './QueuePanel.svelte';
  import SleepTimerButton from './SleepTimerButton.svelte';
  import SleepTimerModal from './SleepTimerModal.svelte';
  import { PLAYER_TEST_IDS } from '$lib/test-ids';

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

      <!-- Playback Controls -->
      <div class="controls-section">
        <Controls />
        <SeekBar
          {currentTime}
          {duration}
          on:seek={handleSeek}
        />
      </div>

      <div class="extra-controls">
        <SleepTimerButton on:click={toggleSleepTimer} />
        <button
          type="button"
          class="queue-btn"
          class:active={showQueue}
          on:click={toggleQueue}
          aria-label="Toggle queue"
          title="Queue ({$queueItems.length})"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z" />
          </svg>
          {#if $queueItems.length > 0}
            <span class="queue-count">{$queueItems.length}</span>
          {/if}
        </button>
      </div>
    </div>
  </div>

  <QueuePanel isOpen={showQueue} on:close={() => (showQueue = false)} />
  <SleepTimerModal isOpen={showSleepTimer} on:close={() => (showSleepTimer = false)} />
{/if}

<style>
  .player-bar {
    position: fixed;
    bottom: 0;
    left: 0;
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

  .queue-btn {
    position: relative;
    padding: var(--spacing-sm);
    background: none;
    border: none;
    color: var(--color-text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    transition: all 0.2s;
  }

  .queue-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-text-primary);
  }

  .queue-btn.active {
    color: var(--color-accent);
  }

  .queue-count {
    position: absolute;
    top: 2px;
    right: 2px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    background-color: var(--color-accent);
    color: white;
    font-size: 0.625rem;
    font-weight: 600;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  @media (max-width: 768px) {
    .player-bar {
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

    .extra-controls {
      display: none;
    }

    .controls-section {
      order: 2;
    }
  }
</style>
