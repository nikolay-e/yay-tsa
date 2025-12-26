<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import {
    isKaraokeEnabled,
    isServerAvailable,
    isProcessing,
    trackStatus,
    isTrackReady,
    karaokeError,
    karaoke,
  } from '../stores/karaoke.store.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';

  const dispatch = createEventDispatcher<{ click: void }>();

  function handleClick(_e: MouseEvent) {
    hapticSelect();

    if (!$isServerAvailable) {
      dispatch('click');
      return;
    }

    if ($isProcessing) {
      dispatch('click');
      return;
    }

    if ($isTrackReady) {
      karaoke.toggle();
      if (!$isKaraokeEnabled) {
        karaoke.setMode('server');
      }
    } else {
      karaoke.requestProcessing();
    }

    dispatch('click');
  }

  $: statusText = $trackStatus?.state === 'PROCESSING'
    ? `${$trackStatus.progressPercent ?? 0}%`
    : $trackStatus?.state === 'READY'
    ? 'OK'
    : $trackStatus?.state === 'FAILED' || $karaokeError
    ? '!'
    : '';

  $: hasError = $trackStatus?.state === 'FAILED' || !!$karaokeError;

  $: buttonTitle = $isProcessing
    ? `Processing ${$trackStatus?.progressPercent ?? 0}%...`
    : $isTrackReady
    ? $isKaraokeEnabled
      ? 'Karaoke ON - tap to disable'
      : 'Karaoke ready - tap to enable'
    : hasError
    ? 'Processing failed - tap to retry'
    : 'Tap to prepare karaoke';
</script>

<button
  type="button"
  class="karaoke-btn"
  class:active={$isKaraokeEnabled}
  class:processing={$isProcessing}
  class:error={hasError}
  on:click={handleClick}
  aria-pressed={$isKaraokeEnabled}
  aria-label={buttonTitle}
  title={buttonTitle}
>
  {#if $isProcessing}
    <div class="spinner"></div>
  {:else}
    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
      <path
        d="M12 3a4 4 0 0 1 4 4v4a4 4 0 0 1-8 0V7a4 4 0 0 1 4-4z"
        fill={$isKaraokeEnabled ? 'currentColor' : 'none'}
        stroke="currentColor"
        stroke-width="2"
      />
      <path d="M19 10v1a7 7 0 0 1-14 0v-1" fill="none" stroke="currentColor" stroke-width="2" />
      <line x1="12" y1="19" x2="12" y2="22" stroke="currentColor" stroke-width="2" />
      <line x1="8" y1="22" x2="16" y2="22" stroke="currentColor" stroke-width="2" />
      {#if $isKaraokeEnabled}
        <line
          x1="4"
          y1="4"
          x2="20"
          y2="20"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
      {/if}
    </svg>
  {/if}
  {#if statusText && $isServerAvailable}
    <span class="status-badge">{statusText}</span>
  {/if}
</button>

<style>
  .karaoke-btn {
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
    touch-action: manipulation;
  }

  .karaoke-btn:hover {
    color: var(--color-text-primary);
    background-color: var(--color-bg-hover);
  }

  .karaoke-btn.active {
    color: var(--color-accent);
  }

  .karaoke-btn.processing {
    color: var(--color-accent);
  }

  .karaoke-btn.error {
    color: var(--color-error, #ef4444);
  }

  .karaoke-btn.error .status-badge {
    background: var(--color-error, #ef4444);
  }

  .spinner {
    width: 18px;
    height: 18px;
    border: 2px solid transparent;
    border-top-color: currentColor;
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  .status-badge {
    position: absolute;
    bottom: 2px;
    right: 2px;
    font-size: 8px;
    font-weight: bold;
    background: var(--color-accent);
    color: white;
    padding: 1px 3px;
    border-radius: 4px;
    line-height: 1;
  }

  @media (max-width: 768px) {
    .karaoke-btn {
      width: 44px;
      height: 44px;
    }
  }
</style>
