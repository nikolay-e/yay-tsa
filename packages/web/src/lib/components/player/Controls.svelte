<script lang="ts">
  import { player, isPlaying, isShuffle, repeatMode } from '../../stores/player.js';
  import { hapticPlayPause, hapticSkip, hapticSelect } from '../../utils/haptics.js';
  import { PLAYER_TEST_IDS } from '$lib/test-ids';

  // Debouncing flag to prevent double-clicks (INP optimization)
  let isTogglingPlayPause = false;

  async function handlePlayPause() {
    if (isTogglingPlayPause) return;

    hapticPlayPause();
    isTogglingPlayPause = true;

    try {
      await player.togglePlayPause();
    } catch (error) {
      console.error('Toggle play/pause error:', error);
    } finally {
      isTogglingPlayPause = false;
    }
  }

  function handlePrevious() {
    hapticSkip();
    player.previous();
  }

  function handleNext() {
    hapticSkip();
    player.next();
  }

  function toggleShuffle() {
    hapticSelect();
    player.toggleShuffle();
  }

  function toggleRepeat() {
    hapticSelect();
    player.toggleRepeat();
  }
</script>

<div class="controls">
  <!-- Shuffle -->
  <button
    type="button"
    class="control-btn"
    class:active={$isShuffle}
    on:click={toggleShuffle}
    aria-label="Toggle shuffle"
    aria-pressed={$isShuffle}
    data-testid={PLAYER_TEST_IDS.SHUFFLE_BUTTON}
  >
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5" />
    </svg>
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

  <!-- Repeat -->
  <button
    type="button"
    class="control-btn"
    class:active={$repeatMode !== 'off'}
    on:click={toggleRepeat}
    aria-label={`Repeat: ${$repeatMode}`}
    aria-pressed={$repeatMode !== 'off'}
    data-testid={PLAYER_TEST_IDS.REPEAT_BUTTON}
  >
    {#if $repeatMode === 'one'}
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M17 1l4 4-4 4" />
        <path d="M3 11V9a4 4 0 0 1 4-4h14" />
        <path d="M7 23l-4-4 4-4" />
        <path d="M21 13v2a4 4 0 0 1-4 4H3" />
        <text x="12" y="16" font-size="8" fill="currentColor" text-anchor="middle">1</text>
      </svg>
    {:else}
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M17 1l4 4-4 4" />
        <path d="M3 11V9a4 4 0 0 1 4-4h14" />
        <path d="M7 23l-4-4 4-4" />
        <path d="M21 13v2a4 4 0 0 1-4 4H3" />
      </svg>
    {/if}
  </button>
</div>

<style>
  .controls {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-sm);
  }

  .control-btn {
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
