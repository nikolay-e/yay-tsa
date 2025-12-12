<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import { isActive, remainingMs, phase, formatTimeRemaining } from '../../stores/sleep-timer.js';
  import { hapticSelect } from '../../utils/haptics.js';
  import { PLAYER_TEST_IDS } from '../../test-ids/index.js';

  const dispatch = createEventDispatcher<{ click: void }>();

  function handleClick() {
    hapticSelect();
    dispatch('click');
  }
</script>

<button
  type="button"
  class="sleep-timer-btn"
  class:active={$isActive}
  on:click={handleClick}
  data-testid={PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON}
  aria-label={$isActive ? `Sleep timer: ${formatTimeRemaining($remainingMs)} remaining` : 'Sleep timer'}
  title={$isActive ? `${formatTimeRemaining($remainingMs)} remaining (${$phase})` : 'Sleep timer'}
>
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
  </svg>
  {#if $isActive}
    <span class="timer-badge">{formatTimeRemaining($remainingMs)}</span>
  {/if}
</button>

<style>
  .sleep-timer-btn {
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

  .sleep-timer-btn:hover {
    color: var(--color-text-primary);
    background-color: var(--color-bg-hover);
  }

  .sleep-timer-btn.active {
    color: var(--color-accent);
  }

  /* Mobile touch targets - minimum 44x44px */
  @media (max-width: 768px) {
    .sleep-timer-btn {
      width: 44px;
      height: 44px;
    }
  }

  .timer-badge {
    position: absolute;
    top: -4px;
    right: -8px;
    padding: 2px 6px;
    background-color: var(--color-accent);
    color: white;
    font-size: 0.625rem;
    font-weight: 600;
    border-radius: 8px;
    white-space: nowrap;
  }
</style>
