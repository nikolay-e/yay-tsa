<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import { player, isPlaying, queueItems } from '../stores/player.store.js';
  import { hapticPlayPause, hapticSkip } from '../../../shared/utils/haptics.js';
  import { PLAYER_TEST_IDS } from '$lib/shared/test-ids';
  import SleepTimerButton from './SleepTimerButton.svelte';
  import KaraokeModeButton from './KaraokeModeButton.svelte';
  import { createLogger } from '@yaytsa/core';

  const log = createLogger('Controls');

  export let showQueue = false;

  const dispatch = createEventDispatcher();

  let isTogglingPlayPause = false;

  async function handlePlayPause() {
    if (isTogglingPlayPause) return;

    hapticPlayPause();
    isTogglingPlayPause = true;

    try {
      await player.togglePlayPause();
    } catch (error) {
      log.error('Toggle play/pause error', error);
    } finally {
      isTogglingPlayPause = false;
    }
  }

  function handlePrevious() {
    hapticSkip();
    player.previous().catch(error => {
      log.error('Previous track error', error);
    });
  }

  function handleNext() {
    hapticSkip();
    player.next().catch(error => {
      log.error('Next track error', error);
    });
  }

  function toggleSleepTimer() {
    dispatch('toggleSleepTimer');
  }

  function toggleQueue() {
    dispatch('toggleQueue');
  }
</script>

<div class="controls">
  <!-- Karaoke Mode (left side) -->
  <KaraokeModeButton />

  <!-- Queue -->
  <button
    type="button"
    class="control-btn"
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

  <!-- Previous -->
  <button
    type="button"
    class="control-btn"
    on:click={handlePrevious}
    aria-label="Previous track"
    data-testid={PLAYER_TEST_IDS.PREVIOUS_BUTTON}
  >
    <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
      <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z" />
    </svg>
  </button>

  <!-- Play/Pause -->
  <button
    type="button"
    class="control-btn play-btn"
    on:click={handlePlayPause}
    aria-label={$isPlaying ? 'Pause' : 'Play'}
    data-testid={PLAYER_TEST_IDS.PLAY_PAUSE_BUTTON}
  >
    {#if $isPlaying}
      <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor">
        <path d="M6 4h4v16H6zM14 4h4v16h-4z" />
      </svg>
    {:else}
      <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor">
        <path d="M8 5v14l11-7z" />
      </svg>
    {/if}
  </button>

  <!-- Next -->
  <button
    type="button"
    class="control-btn"
    on:click={handleNext}
    aria-label="Next track"
    data-testid={PLAYER_TEST_IDS.NEXT_BUTTON}
  >
    <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
      <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z" />
    </svg>
  </button>

  <!-- Sleep Timer -->
  <SleepTimerButton on:click={toggleSleepTimer} />
</div>

<style>
  .controls {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-sm);
  }

  .control-btn {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    padding: 0;
    background: none;
    border: none;
    border-radius: 50%;
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all 0.2s;
    /* Eliminate 300ms click delay and prevent double-tap zoom on mobile */
    touch-action: manipulation;
  }

  .control-btn:hover {
    color: var(--color-text-primary);
    background-color: var(--color-bg-hover);
  }

  .control-btn.active {
    color: var(--color-accent);
  }

  .play-btn {
    width: 48px;
    height: 48px;
    background-color: var(--color-accent);
    color: white;
  }

  .play-btn:hover {
    background-color: var(--color-accent-hover);
    transform: scale(1.05);
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

  /* Mobile touch targets - minimum 44x44px */
  @media (max-width: 768px) {
    .control-btn {
      width: 44px;
      height: 44px;
    }

    .play-btn {
      width: 48px;
      height: 48px;
    }

    .controls {
      gap: var(--spacing-md); /* Increase gap for easier tap */
    }
  }
</style>
