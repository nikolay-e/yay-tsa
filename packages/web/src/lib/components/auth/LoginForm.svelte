<script lang="ts">
  import { auth, isLoading, error as authError } from '../../stores/auth.js';
  import { config } from '../../stores/config.js';
  import Button from '../ui/Button.svelte';
  import Input from '../ui/Input.svelte';

  let username = '';
  let password = '';
  let error = '';

  // Get server URL from environment config
  const serverUrl = $config.serverUrl;

  async function handleSubmit() {
    error = '';

    // Check if server URL is configured
    if (!serverUrl) {
      error = 'Server URL is not configured. Please set JELLYFIN_SERVER_URL environment variable.';
      return;
    }

    if (!username || !password) {
      error = 'Username and password are required';
      return;
    }

    try {
      await auth.login(serverUrl, username, password);
    } catch (err) {
      error = (err as Error).message;
    }
  }
</script>

<div class="login-form">
  <div class="form-header">
    <h1>Jellyfin Mini Player</h1>
    <p>Sign in to start listening</p>
    {#if serverUrl}
      <p class="server-info">Connected to: <strong>{new URL(serverUrl).hostname}</strong></p>
    {/if}
  </div>

  <form on:submit|preventDefault={handleSubmit}>
    <Input
      id="username"
      type="text"
      label="Username"
      bind:value={username}
      placeholder="Enter your username"
      autocomplete="username"
      required
    />

    <Input
      id="password"
      type="password"
      label="Password"
      bind:value={password}
      placeholder="Enter your password"
      autocomplete="current-password"
      required
    />

    {#if error || $authError}
      <div class="error-banner">
        {error || $authError}
      </div>
    {/if}

    <Button type="submit" disabled={$isLoading} size="lg">
      {#if $isLoading}
        Connecting...
      {:else}
        Sign In
      {/if}
    </Button>
  </form>
</div>

<style>
  .login-form {
    max-width: 400px;
    margin: 0 auto;
    padding: var(--spacing-xl);
  }

  .form-header {
    text-align: center;
    margin-bottom: var(--spacing-xl);
  }

  .form-header h1 {
    font-size: 2rem;
    margin-bottom: var(--spacing-sm);
    color: var(--color-accent);
  }

  .form-header p {
    color: var(--color-text-secondary);
  }

  .server-info {
    font-size: 0.875rem;
    margin-top: var(--spacing-sm);
    color: var(--color-text-secondary);
  }

  .server-info strong {
    color: var(--color-accent);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .error-banner {
    padding: var(--spacing-md);
    background-color: rgba(226, 33, 52, 0.1);
    border: 1px solid var(--color-error);
    border-radius: var(--radius-sm);
    color: var(--color-error);
    font-size: 0.875rem;
  }

  /* Mobile optimization */
  @media (max-width: 768px) {
    .login-form {
      padding: var(--spacing-md);
    }

    .form-header h1 {
      font-size: 1.75rem;
    }

    .form-header p {
      font-size: 0.9375rem;
    }
  }

  @media (max-width: 375px) {
    .login-form {
      padding: var(--spacing-sm);
    }

    .form-header h1 {
      font-size: 1.5rem;
    }
  }
</style>
