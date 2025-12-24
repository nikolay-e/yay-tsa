<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import Button from '../ui/Button.svelte';
  import { hapticSelect } from '../../utils/haptics.js';
  import { logger } from '../../utils/logger.js';
  import { passkey } from '../../stores/passkey.js';

  export let isOpen = false;

  const dispatch = createEventDispatcher<{ close: void; registered: void }>();

  let isRegistering = false;
  let error = '';
  let registrationSuccess = false;

  function handleClose() {
    if (!isRegistering && registrationSuccess) {
      dispatch('close');
    }
  }

  function handleBackdropClick(event: MouseEvent) {
    if (event.target === event.currentTarget && registrationSuccess) {
      handleClose();
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && registrationSuccess) {
      handleClose();
    }
  }

  async function handleRegisterPasskey() {
    hapticSelect();
    isRegistering = true;
    error = '';

    try {
      await passkey.registerPasskey('Device Passkey');
      registrationSuccess = true;
      logger.info('[Passkey] Registration successful');

      setTimeout(() => {
        dispatch('registered');
        handleClose();
      }, 2000);
    } catch (err) {
      logger.error('[Passkey] Registration error:', err);
      error = err instanceof Error ? err.message : 'Failed to register passkey';
    } finally {
      isRegistering = false;
    }
  }

  function handleRetry() {
    hapticSelect();
    error = '';
    registrationSuccess = false;
  }
</script>

<svelte:window on:keydown={handleKeydown} />

{#if isOpen}
  <!-- svelte-ignore a11y-click-events-have-key-events -->
  <!-- svelte-ignore a11y-no-static-element-interactions -->
  <div class="modal-backdrop" on:click={handleBackdropClick}>
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="passkey-registration-title">
      <div class="modal-header">
        <h2 id="passkey-registration-title">Secure Your Account with Passkey</h2>
      </div>

      <div class="modal-body">
        {#if registrationSuccess}
          <!-- Success state -->
          <div class="success-state">
            <div class="success-icon">
              <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            </div>
            <h3>Passkey Registered!</h3>
            <p>Your account is now secured with passkey authentication.</p>
          </div>
        {:else}
          <!-- Registration explanation -->
          <div class="explanation">
            <div class="benefit-list">
              <div class="benefit-item">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z" />
                </svg>
                <div>
                  <strong>More Secure</strong>
                  <p>Passkeys are phishing-resistant and tied to your device</p>
                </div>
              </div>
              <div class="benefit-item">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9z" />
                </svg>
                <div>
                  <strong>Faster Sign-In</strong>
                  <p>Use biometrics or security key instead of passwords</p>
                </div>
              </div>
              <div class="benefit-item">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z" />
                </svg>
                <div>
                  <strong>Device-Bound</strong>
                  <p>Your passkey stays on this device and can't be stolen</p>
                </div>
              </div>
            </div>

            {#if error}
              <div class="error-banner">
                {error}
              </div>
            {/if}
          </div>

          <div class="modal-footer">
            {#if error}
              <Button variant="secondary" on:click={handleRetry} disabled={isRegistering}>
                Try Again
              </Button>
            {:else}
              <Button on:click={handleRegisterPasskey} disabled={isRegistering} size="lg">
                {#if isRegistering}
                  <span class="loading-spinner"></span>
                  Setting Up Passkey...
                {:else}
                  Set Up Passkey
                {/if}
              </Button>
            {/if}
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
    max-width: 480px;
    width: 100%;
    max-height: 90vh;
    overflow-y: auto;
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

  .explanation {
    margin-bottom: var(--spacing-lg);
  }

  .benefit-list {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-lg);
    margin-bottom: var(--spacing-lg);
  }

  .benefit-item {
    display: flex;
    gap: var(--spacing-md);
    align-items: flex-start;
  }

  .benefit-item svg {
    flex-shrink: 0;
    color: var(--color-accent);
    margin-top: 2px;
  }

  .benefit-item strong {
    display: block;
    color: var(--color-text-primary);
    margin-bottom: var(--spacing-xs);
    font-size: 0.9375rem;
  }

  .benefit-item p {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 0.875rem;
    line-height: 1.5;
  }

  .error-banner {
    padding: var(--spacing-md);
    background-color: rgba(226, 33, 52, 0.1);
    border: 1px solid var(--color-error);
    border-radius: var(--radius-sm);
    color: var(--color-error);
    font-size: 0.875rem;
  }

  .success-state {
    text-align: center;
    padding: var(--spacing-xl) 0;
  }

  .success-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 80px;
    border-radius: 50%;
    background-color: rgba(76, 175, 80, 0.1);
    color: var(--color-success, #4caf50);
    margin-bottom: var(--spacing-lg);
  }

  .success-state h3 {
    margin: 0 0 var(--spacing-sm) 0;
    font-size: 1.5rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .success-state p {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 0.9375rem;
  }

  .modal-footer {
    padding-top: var(--spacing-md);
    border-top: 1px solid var(--color-border);
  }

  .modal-footer :global(.btn) {
    width: 100%;
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
      max-height: calc(100vh - var(--spacing-xl));
    }

    .modal-header h2 {
      font-size: 1.125rem;
    }

    .benefit-list {
      gap: var(--spacing-md);
    }
  }
</style>
