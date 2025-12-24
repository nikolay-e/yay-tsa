<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import Button from '../ui/Button.svelte';
  import { hapticSelect } from '../../utils/haptics.js';
  import { logger } from '../../utils/logger.js';
  import { auth } from '../../stores/auth.js';

  export let isOpen = false;

  const dispatch = createEventDispatcher<{ verified: void; fallback: void }>();

  let isVerifying = false;
  let error = '';

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && !isVerifying) {
      handleFallback();
    }
  }

  async function handleVerify() {
    hapticSelect();
    isVerifying = true;
    error = '';

    try {
      // TODO: Replace with actual passkey verification API call
      // const result = await passkeyService.verify();

      // Simulate API call
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Mock success for now
      const mockSuccess = Math.random() > 0.2; // 80% success rate for testing

      if (mockSuccess) {
        logger.info('[Passkey] Verification successful');
        dispatch('verified');
      } else {
        throw new Error('Passkey verification failed');
      }
    } catch (err) {
      logger.error('[Passkey] Verification error:', err);
      error = err instanceof Error ? err.message : 'Failed to verify passkey';
    } finally {
      isVerifying = false;
    }
  }

  function handleFallback() {
    hapticSelect();
    logger.info('[Passkey] User chose to sign in with password instead');

    // Clear session and redirect to login
    auth.logout();
    dispatch('fallback');
  }

  function handleRetry() {
    hapticSelect();
    error = '';
  }
</script>

<svelte:window on:keydown={handleKeydown} />

{#if isOpen}
  <div class="modal-backdrop">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="passkey-verification-title">
      <div class="modal-header">
        <h2 id="passkey-verification-title">Verify Your Identity</h2>
      </div>

      <div class="modal-body">
        <div class="verification-content">
          <div class="shield-icon">
            <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </div>

          <p class="verification-message">
            Use your passkey to verify your identity and continue to your music library.
          </p>

          {#if error}
            <div class="error-banner">
              {error}
            </div>
          {/if}

          <div class="action-buttons">
            {#if error}
              <Button on:click={handleRetry} disabled={isVerifying} size="lg">
                Try Again
              </Button>
            {:else}
              <Button on:click={handleVerify} disabled={isVerifying} size="lg">
                {#if isVerifying}
                  <span class="loading-spinner"></span>
                  Verifying...
                {:else}
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z" />
                  </svg>
                  Use Passkey
                {/if}
              </Button>
            {/if}

            <button type="button" class="fallback-link" on:click={handleFallback} disabled={isVerifying}>
              Sign in with password instead
            </button>
          </div>
        </div>
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
    box-shadow: var(--shadow-xl);
  }

  .modal-header {
    padding: var(--spacing-lg);
    border-bottom: 1px solid var(--color-border);
    text-align: center;
  }

  .modal-header h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .modal-body {
    padding: var(--spacing-lg);
  }

  .verification-content {
    text-align: center;
  }

  .shield-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 80px;
    border-radius: 50%;
    background-color: rgba(33, 150, 243, 0.1);
    color: var(--color-accent);
    margin-bottom: var(--spacing-lg);
  }

  .verification-message {
    color: var(--color-text-secondary);
    font-size: 0.9375rem;
    line-height: 1.6;
    margin: 0 0 var(--spacing-lg) 0;
  }

  .error-banner {
    padding: var(--spacing-md);
    background-color: rgba(226, 33, 52, 0.1);
    border: 1px solid var(--color-error);
    border-radius: var(--radius-sm);
    color: var(--color-error);
    font-size: 0.875rem;
    margin-bottom: var(--spacing-lg);
    text-align: left;
  }

  .action-buttons {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .action-buttons :global(.btn) {
    width: 100%;
  }

  .fallback-link {
    background: none;
    border: none;
    color: var(--color-text-secondary);
    font-size: 0.875rem;
    cursor: pointer;
    padding: var(--spacing-sm);
    transition: color 0.2s;
  }

  .fallback-link:hover:not(:disabled) {
    color: var(--color-accent);
    text-decoration: underline;
  }

  .fallback-link:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .loading-spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid rgba(255, 255, 255, 0.3);
    border-top-color: white;
    border-radius: 50%;
    animation: spin 0.6s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (max-width: 480px) {
    .modal {
      margin: var(--spacing-md);
    }

    .modal-header h2 {
      font-size: 1.125rem;
    }

    .verification-message {
      font-size: 0.875rem;
    }
  }
</style>
