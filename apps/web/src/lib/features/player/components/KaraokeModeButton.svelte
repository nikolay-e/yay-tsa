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
    type KaraokeMode,
  } from '../karaoke.store.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';

  const dispatch = createEventDispatcher<{ click: void }>();

  let showMenu = false;

  function handleClick(_e: MouseEvent) {
    hapticSelect();

    // Show menu to manage karaoke mode
    if ($isServerAvailable) {
      showMenu = !showMenu;
    }
    dispatch('click');
  }

  function selectMode(mode: KaraokeMode) {
    karaoke.setMode(mode);
    if (mode !== 'off') {
      if (!$isKaraokeEnabled) karaoke.toggle();
    } else {
      if ($isKaraokeEnabled) karaoke.toggle();
    }
    showMenu = false;
  }

  function handlePrepare() {
    karaoke.requestProcessing();
    showMenu = false;
  }

  function handleClickOutside(_e: MouseEvent) {
    if (showMenu) {
      showMenu = false;
    }
  }

  $: statusText = $trackStatus?.state === 'PROCESSING'
    ? `${$trackStatus.progressPercent ?? 0}%`
    : $trackStatus?.state === 'READY'
    ? 'OK'
    : $trackStatus?.state === 'FAILED' || $karaokeError
    ? '!'
    : '';

  $: hasError = $trackStatus?.state === 'FAILED' || !!$karaokeError;
  $: errorMessage = $trackStatus?.message || $karaokeError || 'Processing failed';

  $: buttonTitle = $isProcessing
    ? 'Processing...'
    : $isKaraokeEnabled
    ? 'AI Karaoke (vocals removed)'
    : 'Enable karaoke mode';
</script>

<svelte:window on:click={handleClickOutside} />

<div class="karaoke-container">
  <button
    type="button"
    class="karaoke-btn"
    class:active={$isKaraokeEnabled}
    class:processing={$isProcessing}
    class:error={hasError}
    on:click|stopPropagation={handleClick}
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

  {#if showMenu}
    <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
    <div class="karaoke-menu" on:click|stopPropagation>
      <div class="menu-header">Karaoke Mode</div>

      {#if hasError}
        <div class="error-message">
          <span class="error-icon">⚠</span>
          <span class="error-text">{errorMessage}</span>
        </div>
      {/if}

      <button
        type="button"
        class="menu-item"
        class:selected={!$isKaraokeEnabled}
        on:click={() => selectMode('off')}
      >
        <span class="item-icon">--</span>
        <span class="item-text">Off</span>
      </button>

      {#if $isServerAvailable}
        <button
          type="button"
          class="menu-item"
          class:selected={$isKaraokeEnabled}
          disabled={!$isTrackReady && !$isProcessing}
          on:click={() => selectMode('server')}
        >
          <span class="item-icon">AI</span>
          <span class="item-text">
            AI Quality
            <span class="item-desc">
              {#if $isTrackReady}
                Ready to play
              {:else if $isProcessing}
                Processing {$trackStatus?.progressPercent ?? 0}%
              {:else}
                Needs preparation
              {/if}
            </span>
          </span>
        </button>

        {#if !$isTrackReady && !$isProcessing}
          <button type="button" class="menu-item prepare-btn" on:click={handlePrepare}>
            <span class="item-icon">+</span>
            <span class="item-text">Prepare AI Karaoke</span>
          </button>
        {/if}
      {/if}
    </div>
  {/if}
</div>

<style>
  .karaoke-container {
    position: relative;
  }

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

  .karaoke-menu {
    position: absolute;
    bottom: 100%;
    left: 50%;
    transform: translateX(-50%);
    margin-bottom: 8px;
    background: var(--color-bg-elevated);
    border: 1px solid var(--color-border);
    border-radius: 12px;
    padding: 8px;
    min-width: 180px;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
    z-index: 100;
  }

  .menu-header {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--color-text-secondary);
    padding: 4px 8px 8px;
    letter-spacing: 0.5px;
  }

  .error-message {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 8px 10px;
    margin-bottom: 8px;
    background: rgba(239, 68, 68, 0.1);
    border: 1px solid rgba(239, 68, 68, 0.3);
    border-radius: 8px;
    color: var(--color-error, #ef4444);
    font-size: 12px;
    line-height: 1.4;
  }

  .error-icon {
    flex-shrink: 0;
  }

  .error-text {
    word-break: break-word;
  }

  .menu-item {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 10px 8px;
    background: none;
    border: none;
    border-radius: 8px;
    color: var(--color-text-primary);
    cursor: pointer;
    text-align: left;
    transition: background 0.15s;
  }

  .menu-item:hover:not(:disabled) {
    background: var(--color-bg-hover);
  }

  .menu-item:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .menu-item.selected {
    background: var(--color-accent-bg);
    color: var(--color-accent);
  }

  .item-icon {
    font-size: 16px;
    width: 24px;
    text-align: center;
  }

  .item-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .item-desc {
    font-size: 11px;
    color: var(--color-text-secondary);
  }

  .prepare-btn {
    margin-top: 4px;
    border-top: 1px solid var(--color-border);
    padding-top: 12px;
  }

  /* Mobile touch targets */
  @media (max-width: 768px) {
    .karaoke-btn {
      width: 44px;
      height: 44px;
    }

    .karaoke-menu {
      min-width: 200px;
    }
  }
</style>
