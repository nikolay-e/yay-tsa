<script lang="ts">
  import { onMount } from 'svelte';
  import Button from '../ui/Button.svelte';
  import { hapticSelect } from '../../utils/haptics.js';
  import { logger } from '../../utils/logger.js';

  interface Passkey {
    id: string;
    name: string;
    createdAt: Date;
  }

  let passkeys: Passkey[] = [];
  let isLoading = true;
  let isAdding = false;
  let error = '';
  let deleteConfirmId: string | null = null;

  onMount(async () => {
    await loadPasskeys();
  });

  async function loadPasskeys() {
    isLoading = true;
    error = '';

    try {
      // TODO: Replace with actual API call to fetch passkeys
      // const result = await passkeyService.list();

      // Mock data for now
      await new Promise(resolve => setTimeout(resolve, 500));

      passkeys = [
        {
          id: '1',
          name: 'MacBook Pro Touch ID',
          createdAt: new Date('2024-01-15T10:30:00'),
        },
        {
          id: '2',
          name: 'iPhone Face ID',
          createdAt: new Date('2024-02-20T15:45:00'),
        },
      ];

      logger.info(`[Passkey] Loaded ${passkeys.length} passkeys`);
    } catch (err) {
      logger.error('[Passkey] Failed to load passkeys:', err);
      error = err instanceof Error ? err.message : 'Failed to load passkeys';
    } finally {
      isLoading = false;
    }
  }

  async function handleAddPasskey() {
    hapticSelect();
    isAdding = true;
    error = '';

    try {
      // TODO: Replace with actual API call to add passkey
      // const result = await passkeyService.register();

      await new Promise(resolve => setTimeout(resolve, 1500));

      const mockSuccess = Math.random() > 0.2;

      if (mockSuccess) {
        logger.info('[Passkey] Added new passkey');
        await loadPasskeys();
      } else {
        throw new Error('Failed to add passkey');
      }
    } catch (err) {
      logger.error('[Passkey] Add passkey error:', err);
      error = err instanceof Error ? err.message : 'Failed to add passkey';
    } finally {
      isAdding = false;
    }
  }

  function handleDeleteClick(passkeyId: string) {
    hapticSelect();
    deleteConfirmId = passkeyId;
  }

  function handleCancelDelete() {
    hapticSelect();
    deleteConfirmId = null;
  }

  async function handleConfirmDelete(passkeyId: string) {
    hapticSelect();
    error = '';

    try {
      // TODO: Replace with actual API call to delete passkey
      // await passkeyService.delete(passkeyId);

      await new Promise(resolve => setTimeout(resolve, 500));

      logger.info(`[Passkey] Deleted passkey: ${passkeyId}`);
      await loadPasskeys();
      deleteConfirmId = null;
    } catch (err) {
      logger.error('[Passkey] Delete passkey error:', err);
      error = err instanceof Error ? err.message : 'Failed to delete passkey';
      deleteConfirmId = null;
    }
  }

  function formatDate(date: Date): string {
    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  }

  $: canDeletePasskey = passkeys.length > 1;
</script>

<div class="passkey-settings">
  <div class="settings-header">
    <h2>Passkeys</h2>
    <p class="settings-description">
      Manage passkeys for secure, passwordless authentication
    </p>
  </div>

  {#if error}
    <div class="error-banner">
      {error}
    </div>
  {/if}

  {#if isLoading}
    <div class="loading-state">
      <div class="spinner"></div>
      <p>Loading passkeys...</p>
    </div>
  {:else}
    <div class="passkey-list">
      {#each passkeys as passkey (passkey.id)}
        <div class="passkey-item">
          <div class="passkey-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
              <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z" />
            </svg>
          </div>
          <div class="passkey-info">
            <h3>{passkey.name}</h3>
            <p class="passkey-date">Created {formatDate(passkey.createdAt)}</p>
          </div>
          <div class="passkey-actions">
            {#if deleteConfirmId === passkey.id}
              <div class="delete-confirm">
                <span class="confirm-text">Delete?</span>
                <button
                  type="button"
                  class="confirm-btn danger"
                  on:click={() => handleConfirmDelete(passkey.id)}
                >
                  Yes
                </button>
                <button
                  type="button"
                  class="confirm-btn"
                  on:click={handleCancelDelete}
                >
                  No
                </button>
              </div>
            {:else}
              <button
                type="button"
                class="delete-btn"
                on:click={() => handleDeleteClick(passkey.id)}
                disabled={!canDeletePasskey}
                title={canDeletePasskey ? 'Delete passkey' : 'Cannot delete last passkey'}
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="3 6 5 6 21 6" />
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                </svg>
              </button>
            {/if}
          </div>
        </div>
      {/each}
    </div>

    <div class="add-passkey-section">
      <Button on:click={handleAddPasskey} disabled={isAdding} variant="secondary">
        {#if isAdding}
          <span class="loading-spinner"></span>
          Adding Passkey...
        {:else}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          Add New Passkey
        {/if}
      </Button>
    </div>
  {/if}
</div>

<style>
  .passkey-settings {
    max-width: 600px;
  }

  .settings-header {
    margin-bottom: var(--spacing-lg);
  }

  .settings-header h2 {
    margin: 0 0 var(--spacing-xs) 0;
    font-size: 1.5rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .settings-description {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 0.9375rem;
  }

  .error-banner {
    padding: var(--spacing-md);
    background-color: rgba(226, 33, 52, 0.1);
    border: 1px solid var(--color-error);
    border-radius: var(--radius-sm);
    color: var(--color-error);
    font-size: 0.875rem;
    margin-bottom: var(--spacing-lg);
  }

  .loading-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-xl);
    color: var(--color-text-secondary);
  }

  .spinner {
    width: 32px;
    height: 32px;
    border: 3px solid var(--color-bg-tertiary);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .passkey-list {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
    margin-bottom: var(--spacing-lg);
  }

  .passkey-item {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-md);
    background-color: var(--color-bg-secondary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
  }

  .passkey-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 48px;
    height: 48px;
    border-radius: var(--radius-md);
    background-color: rgba(33, 150, 243, 0.1);
    color: var(--color-accent);
    flex-shrink: 0;
  }

  .passkey-info {
    flex: 1;
    min-width: 0;
  }

  .passkey-info h3 {
    margin: 0 0 var(--spacing-xs) 0;
    font-size: 0.9375rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .passkey-date {
    margin: 0;
    font-size: 0.8125rem;
    color: var(--color-text-tertiary);
  }

  .passkey-actions {
    flex-shrink: 0;
  }

  .delete-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    padding: 0;
    background: none;
    border: none;
    border-radius: var(--radius-sm);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all 0.2s;
  }

  .delete-btn:hover:not(:disabled) {
    background-color: var(--color-bg-hover);
    color: var(--color-error);
  }

  .delete-btn:disabled {
    opacity: 0.3;
    cursor: not-allowed;
  }

  .delete-confirm {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
  }

  .confirm-text {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
  }

  .confirm-btn {
    padding: var(--spacing-xs) var(--spacing-sm);
    background-color: var(--color-bg-tertiary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    color: var(--color-text-primary);
    font-size: 0.875rem;
    cursor: pointer;
    transition: all 0.2s;
  }

  .confirm-btn:hover {
    background-color: var(--color-bg-hover);
  }

  .confirm-btn.danger {
    background-color: var(--color-error);
    border-color: var(--color-error);
    color: white;
  }

  .confirm-btn.danger:hover {
    opacity: 0.9;
  }

  .add-passkey-section :global(.btn) {
    width: 100%;
  }

  .loading-spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid var(--color-text-secondary);
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.6s linear infinite;
  }

  @media (max-width: 480px) {
    .passkey-item {
      padding: var(--spacing-sm);
    }

    .passkey-icon {
      width: 40px;
      height: 40px;
    }

    .passkey-info h3 {
      font-size: 0.875rem;
    }

    .passkey-date {
      font-size: 0.75rem;
    }

    .delete-confirm {
      flex-direction: column;
      gap: var(--spacing-xs);
    }
  }
</style>
