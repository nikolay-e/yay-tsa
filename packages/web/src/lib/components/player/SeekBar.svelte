<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import type { Readable } from 'svelte/store';
  import { formatTime } from '../../utils/time.js';
  import { PLAYER_TEST_IDS } from '$lib/test-ids';

  const dispatch = createEventDispatcher<{ seek: number }>();

  export let currentTime: Readable<number>;
  export let duration: Readable<number>;

  let seeking = false;
  let seekValue = 0;

  $: progress = $duration > 0 ? ($currentTime / $duration) * 100 : 0;
  $: displayTime = seeking ? seekValue : $currentTime;

  function handleSeekStart() {
    seeking = true;
    seekValue = $currentTime;
  }

  function handleSeekChange(event: Event) {
    const target = event.target as HTMLInputElement;
    seekValue = parseFloat(target.value);
  }

  function handleSeekEnd(event: Event) {
    const target = event.target as HTMLInputElement;
    const newTime = parseFloat(target.value);
    seeking = false;

    // Dispatch seek event to parent using Svelte's dispatcher
    dispatch('seek', newTime);
  }
</script>

<div class="seek-bar">
  <span class="time" data-testid={PLAYER_TEST_IDS.CURRENT_TIME}>{formatTime(displayTime)}</span>

  <div class="slider-container">
    <input
      type="range"
      min="0"
      max={$duration || 0}
      step="0.1"
      value={displayTime}
      class="slider"
      style:--progress="{progress}%"
      on:mousedown={handleSeekStart}
      on:touchstart={handleSeekStart}
      on:input={handleSeekChange}
      on:change={handleSeekEnd}
      disabled={!$duration}
      aria-label="Seek position"
      aria-valuemin="0"
      aria-valuemax={$duration || 0}
      aria-valuenow={displayTime}
      data-testid={PLAYER_TEST_IDS.SEEK_SLIDER}
    />
  </div>

  <span class="time" data-testid={PLAYER_TEST_IDS.TOTAL_TIME}>{formatTime($duration)}</span>
</div>

<style>
  .seek-bar {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    width: 100%;
  }

  .time {
    font-size: 0.75rem;
    color: var(--color-text-secondary);
    min-width: 40px;
    text-align: center;
  }

  .slider-container {
    flex: 1;
    position: relative;
  }

  .slider {
    width: 100%;
    height: 4px;
    -webkit-appearance: none;
    appearance: none;
    background: transparent;
    outline: none;
    cursor: pointer;
    /* Eliminate 300ms click delay on mobile */
    touch-action: manipulation;
  }

  .slider:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }

  /* Track */
  .slider::-webkit-slider-runnable-track {
    width: 100%;
    height: 4px;
    background: linear-gradient(
      to right,
      var(--color-accent) 0%,
      var(--color-accent) var(--progress),
      var(--color-bg-tertiary) var(--progress),
      var(--color-bg-tertiary) 100%
    );
    border-radius: 2px;
  }

  .slider::-moz-range-track {
    width: 100%;
    height: 4px;
    background: var(--color-bg-tertiary);
    border-radius: 2px;
  }

  .slider::-moz-range-progress {
    background: var(--color-accent);
    height: 4px;
    border-radius: 2px;
  }

  /* Thumb */
  .slider::-webkit-slider-thumb {
    -webkit-appearance: none;
    appearance: none;
    width: 12px;
    height: 12px;
    background: white;
    border-radius: 50%;
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.2s;
  }

  .slider:hover::-webkit-slider-thumb,
  .slider:active::-webkit-slider-thumb {
    opacity: 1;
  }

  .slider::-moz-range-thumb {
    width: 12px;
    height: 12px;
    background: white;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.2s;
  }

  .slider:hover::-moz-range-thumb,
  .slider:active::-moz-range-thumb {
    opacity: 1;
  }

  /* Mobile touch-friendly seek bar */
  @media (max-width: 768px) {
    .slider {
      height: 4px;
      padding: 10px 0; /* Larger touch area */
    }

    .slider::-webkit-slider-runnable-track {
      height: 4px;
    }

    .slider::-moz-range-track {
      height: 4px;
    }

    .slider::-moz-range-progress {
      height: 4px;
    }

    /* Always visible, larger thumb on mobile - centered on track */
    .slider::-webkit-slider-thumb {
      width: 20px;
      height: 20px;
      margin-top: -8px; /* Center thumb on track: (thumb_height - track_height) / 2 */
      opacity: 1;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
    }

    .slider::-moz-range-thumb {
      width: 20px;
      height: 20px;
      opacity: 1;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
    }

    .time {
      font-size: 0.8125rem;
      min-width: 45px;
    }
  }
</style>
