<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import {
    sleepTimer,
    isActive,
    remainingMs,
    phase,
    config,
    formatTimeRemaining,
    type SleepTimerConfig,
  } from '../sleep-timer.store.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';

  export let isOpen = false;

  const dispatch = createEventDispatcher<{ close: void }>();

  // Local state for custom configuration
  let customMusicHours = 1;
  let customMusicMinutes = 0;
  let customNoiseHours = 1;
  let customNoiseMinutes = 0;
  let customCrossfadeMinutes = 5;

  // Sync local state with config when modal opens
  $: if (isOpen && $config) {
    customMusicHours = Math.floor($config.musicDurationMs / (60 * 60 * 1000));
    customMusicMinutes = Math.floor(($config.musicDurationMs % (60 * 60 * 1000)) / (60 * 1000));
    customNoiseHours = Math.floor($config.noiseDurationMs / (60 * 60 * 1000));
    customNoiseMinutes = Math.floor(($config.noiseDurationMs % (60 * 60 * 1000)) / (60 * 1000));
    customCrossfadeMinutes = Math.floor($config.crossfadeDurationMs / (60 * 1000));
  }

  const presetOptions = [
    // With pink noise
    {
      id: '1h+10m+30m',
      label: '1h + 10m + 30m',
      description: 'Music → Crossfade → Pink noise',
    },
    {
      id: '30m+5m+15m',
      label: '30m + 5m + 15m',
      description: 'Music → Crossfade → Pink noise',
    },
    { id: '1m+1m+1m', label: '1m + 1m + 1m', description: 'Test preset (short)' },
    // Without pink noise (fade only)
    { id: '1h+10m', label: '1h + 10m', description: 'Music → Fade out' },
    { id: '30m+5m', label: '30m + 5m', description: 'Music → Fade out' },
    { id: '1m+1m', label: '1m + 1m', description: 'Test preset (fade only)' },
  ];

  function handleClose() {
    dispatch('close');
  }

  function handleBackdropClick(event: MouseEvent) {
    if (event.target === event.currentTarget) {
      handleClose();
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      handleClose();
    }
  }

  function selectPresetAndStart(presetId: string) {
    hapticSelect();
    sleepTimer.applyPreset(presetId);
    sleepTimer.start();
    handleClose();
  }

  function startWithCustomConfig() {
    hapticSelect();
    const customConfig: Partial<SleepTimerConfig> = {
      musicDurationMs: (customMusicHours * 60 + customMusicMinutes) * 60 * 1000,
      noiseDurationMs: (customNoiseHours * 60 + customNoiseMinutes) * 60 * 1000,
      crossfadeDurationMs: customCrossfadeMinutes * 60 * 1000,
    };
    sleepTimer.start(customConfig);
    handleClose();
  }

  function cancelTimer() {
    hapticSelect();
    sleepTimer.cancel();
  }

  function extendTimer(minutes: number) {
    hapticSelect();
    sleepTimer.extendTime(minutes * 60 * 1000);
  }

  function getPhaseLabel(p: string): string {
    switch (p) {
      case 'music':
        return 'Playing music';
      case 'crossfade-to-noise':
        return 'Fading to pink noise';
      case 'noise':
        return 'Pink noise (fading)';
      default:
        return '';
    }
  }
</script>

<svelte:window on:keydown={handleKeydown} />

{#if isOpen}
  <!-- svelte-ignore a11y-click-events-have-key-events -->
  <!-- svelte-ignore a11y-no-static-element-interactions -->
  <div class="modal-backdrop" on:click={handleBackdropClick}>
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="sleep-timer-title">
      <div class="modal-header">
        <h2 id="sleep-timer-title">Sleep Timer</h2>
        <button type="button" class="close-btn" on:click={handleClose} aria-label="Close">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        {#if $isActive}
          <!-- Active timer view -->
          <div class="active-timer">
            <div class="timer-display">
              <span class="timer-value">{formatTimeRemaining($remainingMs)}</span>
              <span class="timer-label">remaining</span>
            </div>
            <div class="phase-indicator">
              <span class="phase-dot" class:active={$phase === 'music'}></span>
              <span class="phase-text">{getPhaseLabel($phase)}</span>
            </div>

            <div class="timer-actions">
              <button type="button" class="action-btn extend" on:click={() => extendTimer(15)}>
                +15 min
              </button>
              <button type="button" class="action-btn extend" on:click={() => extendTimer(30)}>
                +30 min
              </button>
              <button type="button" class="action-btn cancel" on:click={cancelTimer}>
                Cancel
              </button>
            </div>
          </div>
        {:else}
          <!-- Setup view -->
          <div class="presets">
            <h3>Quick Start</h3>
            <div class="preset-grid">
              {#each presetOptions as preset}
                <button
                  type="button"
                  class="preset-btn"
                  on:click={() => selectPresetAndStart(preset.id)}
                >
                  <span class="preset-label">{preset.label}</span>
                  <span class="preset-desc">{preset.description}</span>
                </button>
              {/each}
            </div>
          </div>

          <div class="custom-config">
            <h3>Custom Configuration</h3>

            <div class="config-row">
              <label>
                <span class="config-label">Music duration</span>
                <div class="time-inputs">
                  <input
                    type="number"
                    bind:value={customMusicHours}
                    min="0"
                    max="12"
                    class="time-input"
                  />
                  <span>h</span>
                  <input
                    type="number"
                    bind:value={customMusicMinutes}
                    min="0"
                    max="59"
                    step="5"
                    class="time-input"
                  />
                  <span>m</span>
                </div>
              </label>
            </div>

            <div class="config-row">
              <label>
                <span class="config-label">Pink noise duration</span>
                <div class="time-inputs">
                  <input
                    type="number"
                    bind:value={customNoiseHours}
                    min="0"
                    max="12"
                    class="time-input"
                  />
                  <span>h</span>
                  <input
                    type="number"
                    bind:value={customNoiseMinutes}
                    min="0"
                    max="59"
                    step="5"
                    class="time-input"
                  />
                  <span>m</span>
                </div>
              </label>
            </div>

            <div class="config-row">
              <label>
                <span class="config-label">Crossfade duration</span>
                <div class="time-inputs">
                  <input
                    type="number"
                    bind:value={customCrossfadeMinutes}
                    min="1"
                    max="30"
                    class="time-input"
                  />
                  <span>min</span>
                </div>
              </label>
            </div>
          </div>

          <div class="modal-footer">
            <button type="button" class="start-btn" on:click={startWithCustomConfig}>
              Start Sleep Timer
            </button>
          </div>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .modal-backdrop {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 200;
    padding: var(--spacing-lg);
  }

  .modal {
    background-color: var(--color-bg-primary);
    border-radius: var(--radius-lg);
    max-width: 400px;
    width: 100%;
    max-height: 90vh;
    overflow-y: auto;
    box-shadow: var(--shadow-xl);
  }

  .modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--spacing-lg);
    border-bottom: 1px solid var(--color-border);
  }

  .modal-header h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .close-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    padding: 0;
    background: none;
    border: none;
    border-radius: var(--radius-sm);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all 0.2s;
  }

  .close-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-text-primary);
  }

  .modal-body {
    padding: var(--spacing-lg);
  }

  /* Active timer styles */
  .active-timer {
    text-align: center;
  }

  .timer-display {
    margin-bottom: var(--spacing-lg);
  }

  .timer-value {
    display: block;
    font-size: 3rem;
    font-weight: 700;
    color: var(--color-accent);
    font-variant-numeric: tabular-nums;
  }

  .timer-label {
    display: block;
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    margin-top: var(--spacing-xs);
  }

  .phase-indicator {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-sm);
    margin-bottom: var(--spacing-xl);
  }

  .phase-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: var(--color-text-tertiary);
  }

  .phase-dot.active {
    background-color: var(--color-accent);
    animation: pulse 2s infinite;
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.5; }
  }

  .phase-text {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
  }

  .timer-actions {
    display: flex;
    gap: var(--spacing-sm);
    justify-content: center;
    flex-wrap: wrap;
  }

  .action-btn {
    padding: var(--spacing-sm) var(--spacing-md);
    border: none;
    border-radius: var(--radius-md);
    font-size: 0.875rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
  }

  .action-btn.extend {
    background-color: var(--color-bg-tertiary);
    color: var(--color-text-primary);
  }

  .action-btn.extend:hover {
    background-color: var(--color-bg-hover);
  }

  .action-btn.cancel {
    background-color: var(--color-error);
    color: white;
  }

  .action-btn.cancel:hover {
    opacity: 0.9;
  }

  /* Setup view styles */
  .presets h3,
  .custom-config h3 {
    margin: 0 0 var(--spacing-md) 0;
    font-size: 0.875rem;
    font-weight: 600;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .preset-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--spacing-sm);
    margin-bottom: var(--spacing-xl);
  }

  .preset-btn {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: var(--spacing-md);
    background-color: var(--color-bg-secondary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: all 0.2s;
  }

  .preset-btn:hover {
    background-color: var(--color-bg-hover);
    border-color: var(--color-accent);
  }

  .preset-label {
    font-size: 1rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .preset-desc {
    font-size: 0.75rem;
    color: var(--color-text-tertiary);
    margin-top: var(--spacing-xs);
  }

  .custom-config {
    margin-bottom: var(--spacing-lg);
  }

  .config-row {
    margin-bottom: var(--spacing-md);
  }

  .config-label {
    display: block;
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    margin-bottom: var(--spacing-xs);
  }

  .time-inputs {
    display: flex;
    align-items: center;
    gap: var(--spacing-xs);
  }

  .time-input {
    width: 60px;
    padding: var(--spacing-sm);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background-color: var(--color-bg-secondary);
    color: var(--color-text-primary);
    font-size: 1rem;
    text-align: center;
  }

  .time-input:focus {
    outline: none;
    border-color: var(--color-accent);
  }

  .time-inputs span {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
  }

  .modal-footer {
    padding-top: var(--spacing-md);
    border-top: 1px solid var(--color-border);
  }

  .start-btn {
    width: 100%;
    padding: var(--spacing-md);
    background-color: var(--color-accent);
    color: white;
    border: none;
    border-radius: var(--radius-md);
    font-size: 1rem;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
  }

  .start-btn:hover {
    background-color: var(--color-accent-hover);
  }

  @media (max-width: 480px) {
    .modal {
      margin: var(--spacing-md);
      max-height: calc(100vh - var(--spacing-xl));
    }

    .preset-grid {
      grid-template-columns: 1fr;
    }

    .timer-value {
      font-size: 2.5rem;
    }
  }
</style>
