<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import { formatTime } from '../../utils/time.js';

  const dispatch = createEventDispatcher<{ seek: number }>();

  export let currentTime: number = 0;
  export let duration: number = 0;

  let seeking = false;
  let seekValue = 0;

  $: progress = duration > 0 ? (currentTime / duration) * 100 : 0;
  $: displayTime = seeking ? seekValue : currentTime;

  function handleSeekStart() {
    seeking = true;
    seekValue = currentTime;
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
  <span class="time">{formatTime(displayTime)}</span>

  <div class="slider-container">
    <input
      type="range"
      min="0"
      max={duration || 0}
      step="0.1"
      value={displayTime}
      class="slider"
      style:--progress="{progress}%"
      on:mousedown={handleSeekStart}
      on:touchstart={handleSeekStart}
      on:input={handleSeekChange}
      on:change={handleSeekEnd}
      disabled={!duration}
      aria-label="Seek position"
      aria-valuemin="0"
      aria-valuemax={duration || 0}
      aria-valuenow={displayTime}
    />
  </div>

  <span class="time">{formatTime(duration)}</span>
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
      height: 8px; /* Thicker track for easier touch */
      padding: 8px 0; /* Add touch area around slider */
    }

    .slider::-webkit-slider-runnable-track {
      height: 8px;
    }

    .slider::-moz-range-track {
      height: 8px;
    }

    .slider::-moz-range-progress {
      height: 8px;
    }

    /* Always visible, larger thumb on mobile */
    .slider::-webkit-slider-thumb {
      width: 24px;
      height: 24px;
      opacity: 1; /* Always visible on touch devices */
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3); /* Add shadow for visibility */
    }

    .slider::-moz-range-thumb {
      width: 24px;
      height: 24px;
      opacity: 1; /* Always visible on touch devices */
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
    }

    .time {
      font-size: 0.8125rem; /* Slightly larger for readability */
      min-width: 45px;
    }
  }
</style>
