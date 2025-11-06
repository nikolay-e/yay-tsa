<script lang="ts">
  import { player, isPlaying, volume, isShuffle, repeatMode } from '../../stores/player.js';
  import VolumeModal from './VolumeModal.svelte';
  import { hapticPlayPause, hapticSkip, hapticSelect } from '../../utils/haptics.js';

  let volumeValue = $volume;
  let showVolume = false;
  let showVolumeModal = false;

  $: volumeValue = $volume;

  function handleVolumeChange(event: Event) {
    const target = event.target as HTMLInputElement;
    const newVolume = parseFloat(target.value);
    player.setVolume(newVolume);
  }

  function handleVolumeModalChange(event: CustomEvent<number>) {
    player.setVolume(event.detail);
  }

  function toggleVolumeModal() {
    hapticSelect();
    showVolumeModal = !showVolumeModal;
  }

  function handlePlayPause() {
    hapticPlayPause();
    player.togglePlayPause();
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
    title="Shuffle"
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
    title="Previous"
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
    title={$isPlaying ? 'Pause' : 'Play'}
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
    title="Next"
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
    title={`Repeat: ${$repeatMode}`}
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

  <!-- Volume -->
  <div class="volume-control" role="group" aria-label="Volume control" on:mouseenter={() => (showVolume = true)} on:mouseleave={() => (showVolume = false)}>
    <button type="button" class="control-btn volume-btn" on:click={toggleVolumeModal} title="Volume">
      {#if volumeValue === 0}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
          <path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z" />
        </svg>
      {:else if volumeValue < 0.5}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
          <path d="M7 9v6h4l5 5V4l-5 5H7z" />
        </svg>
      {:else}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
          <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" />
        </svg>
      {/if}
    </button>

    {#if showVolume}
      <div class="volume-slider">
        <input
          type="range"
          min="0"
          max="1"
          step="0.01"
          value={volumeValue}
          on:input={handleVolumeChange}
        />
      </div>
    {/if}
  </div>
</div>

{#if showVolumeModal}
  <VolumeModal
    volume={volumeValue}
    on:change={handleVolumeModalChange}
    on:close={() => (showVolumeModal = false)}
  />
{/if}

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

  .volume-control {
    position: relative;
  }

  .volume-slider {
    position: absolute;
    bottom: 100%;
    left: 50%;
    transform: translateX(-50%);
    padding: var(--spacing-md);
    background-color: var(--color-bg-tertiary);
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-lg);
    margin-bottom: var(--spacing-sm);
  }

  .volume-slider input {
    width: 100px;
    transform: rotate(-90deg);
    transform-origin: center;
  }

  .volume-slider input[type='range'] {
    -webkit-appearance: none;
    appearance: none;
    height: 4px;
    background: var(--color-bg-hover);
    border-radius: 2px;
    outline: none;
  }

  .volume-slider input[type='range']::-webkit-slider-thumb {
    -webkit-appearance: none;
    appearance: none;
    width: 12px;
    height: 12px;
    background: var(--color-accent);
    border-radius: 50%;
    cursor: pointer;
  }

  .volume-slider input[type='range']::-moz-range-thumb {
    width: 12px;
    height: 12px;
    background: var(--color-accent);
    border: none;
    border-radius: 50%;
    cursor: pointer;
  }

  /* Mobile touch targets - minimum 44x44px */
  @media (max-width: 768px) {
    .control-btn {
      width: 44px;
      height: 44px;
    }

    .play-btn {
      width: 56px;
      height: 56px;
    }

    .controls {
      gap: var(--spacing-md); /* Increase gap for easier tap */
    }

    /* Hide hover-based volume slider on mobile, use modal instead */
    .volume-slider {
      display: none;
    }
  }
</style>
