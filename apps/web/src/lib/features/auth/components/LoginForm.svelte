<script lang="ts">
  import { auth, isLoading, error as authError } from '../auth.store.js';
  import { config } from '../../settings/config.store.js';
  import { APP_VERSION } from '@yaytsa/core';
  import Button from '../../../shared/ui/Button.svelte';
  import Input from '../../../shared/ui/Input.svelte';

  let username = '';
  let password = '';
  let rememberMe = false; // Remember Me checkbox state
  let error = '';

  // Get server URL from environment config
  const serverUrl = $config.serverUrl;

  async function handleSubmit() {
    error = '';

    // Check if server URL is configured
    if (!serverUrl) {
      error = 'Server URL is not configured. Please set YAYTSA_SERVER_URL environment variable.';
      return;
    }

    if (!username || !password) {
      error = 'Username and password are required';
      return;
    }

    try {
      await auth.login(serverUrl, username, password, { rememberMe });
    } catch (err) {
      error = (err as Error).message;
    }
  }
</script>

<div class="login-form">
  <div class="form-header">
    <h1>Yaytsa</h1>
    <p>Sign in to start listening</p>
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

    <label class="remember-me" for="remember-me">
      <input id="remember-me" name="rememberMe" type="checkbox" bind:checked={rememberMe} />
      <span>Remember me (keep me signed in)</span>
    </label>

    {#if error || $authError}
      <div class="error-banner">
        {error || $authError?.message}
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

  <footer class="login-footer">
    <small class="version-text">v{APP_VERSION}</small>
  </footer>
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

  form {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .remember-me {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    font-size: 0.9375rem;
    color: var(--color-text-secondary);
    cursor: pointer;
    user-select: none;
  }

  .remember-me input[type='checkbox'] {
    width: 18px;
    height: 18px;
    cursor: pointer;
    accent-color: var(--color-accent);
  }

  .remember-me span {
    line-height: 1.5;
  }

  .remember-me:hover span {
    color: var(--color-text-primary);
  }

  .error-banner {
    padding: var(--spacing-md);
    background-color: rgba(226, 33, 52, 0.1);
    border: 1px solid var(--color-error);
    border-radius: var(--radius-sm);
    color: var(--color-error);
    font-size: 0.875rem;
  }

  .login-footer {
    position: relative;
    margin-top: var(--spacing-xl);
    text-align: right;
  }

  .version-text {
    font-size: 0.75rem;
    color: var(--color-text-secondary);
    opacity: 0.7;
    white-space: nowrap;
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

    .version-text {
      font-size: 0.875rem;
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
