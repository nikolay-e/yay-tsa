<script lang="ts">
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth, isAuthenticated } from '../lib/features/auth/stores/auth.store.js';
  import { currentTrack, player } from '../lib/features/player/stores/player.store.js';
  import PlayerBar from '../lib/features/player/components/PlayerBar.svelte';
  import BottomTabBar from '../lib/features/navigation/components/BottomTabBar.svelte';
  import { cacheManager } from '../lib/cache/cache-manager.js';
  import { createLogger } from '../lib/shared/utils/logger.js';
  import '../app.css';

  const log = createLogger('App');

  // SvelteKit page props (external reference only)
  export const data = {};
  export const params = {};

  let loading = true;

  // Global error handlers for catching unhandled errors
  onMount(() => {
    const handleUnhandledRejection = (event: PromiseRejectionEvent) => {
      log.error('Unhandled promise rejection', event.reason, {
        promise: String(event.promise),
      });
    };

    const handleGlobalError = (event: ErrorEvent) => {
      log.error('Uncaught error', event.error, {
        message: event.message,
        filename: event.filename,
        lineno: event.lineno,
        colno: event.colno,
      });
    };

    window.addEventListener('unhandledrejection', handleUnhandledRejection);
    window.addEventListener('error', handleGlobalError);

    return () => {
      window.removeEventListener('unhandledrejection', handleUnhandledRejection);
      window.removeEventListener('error', handleGlobalError);
    };
  });

  // Initialize cache and restore session
  onMount(async () => {
    // Initialize cache manager
    try {
      await cacheManager.init();
      log.info('Cache manager initialized');
    } catch (error) {
      log.error('Failed to initialize cache manager', error);
    }

    // Try to restore session from sessionStorage or localStorage
    const restored = await auth.restoreSession();

    loading = false;

    if (!restored && $page.url.pathname !== '/login') {
      // Redirect to login if not authenticated and not already on login page
      goto('/login');
    }
  });

  // Handle app background/foreground transitions (mobile playback fix)
  // When hidden: stop RAF updates to save CPU
  // When visible: resume AudioContext if suspended (iOS/Android requirement)
  onMount(() => {
    const handleVisibilityChange = async () => {
      if (document.visibilityState === 'hidden') {
        player.stopUiLoop();
      } else {
        // Resume AudioContext when returning from background (mobile fix)
        try {
          await player.resumeAudioContext();
        } catch (error) {
          log.warn('Failed to resume AudioContext', { error: String(error) });
        }
      }
    };

    // Blur/focus handlers for additional reliability on mobile
    const handleBlur = () => player.stopUiLoop();
    const handleFocus = () => {
      player.resumeAudioContext().catch(error => {
        log.warn('Failed to resume AudioContext on focus', { error: String(error) });
      });
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('blur', handleBlur);
    window.addEventListener('focus', handleFocus);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('blur', handleBlur);
      window.removeEventListener('focus', handleFocus);
    };
  });

  // Redirect authenticated users away from login page
  $: if ($isAuthenticated && $page.url.pathname === '/login') {
    goto('/');
  }

  // Redirect non-authenticated users to login page (after initial loading)
  $: if (!loading && !$isAuthenticated && $page.url.pathname !== '/login') {
    goto('/login');
  }

  $: showPlayerBar = $currentTrack !== null;
  // Content padding must account for bottom tabs + player bar + safe area
  // Bottom tabs: 52px, Player bar: 64px (min-height), Safe area: dynamic
  // ALWAYS reserve space for player bar to prevent CLS when it appears
  const contentPadding = 'calc(52px + 64px + var(--safe-area-inset-bottom))';
</script>

{#if loading}
  <div class="loading-screen">
    <div class="spinner"></div>
    <p>Loading...</p>
  </div>
{:else}
  <div class="app">
    {#if $isAuthenticated && $page.url.pathname !== '/login'}
      <!-- Navigation -->
      <nav class="sidebar">
        <div class="logo">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
          </svg>
          <span>Yaytsa</span>
        </div>

        <ul class="nav-links">
          <li>
            <a href="/" class:active={$page.url.pathname === '/'}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                <path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z" />
              </svg>
              Home
            </a>
          </li>
          <li>
            <a href="/albums" class:active={$page.url.pathname.startsWith('/albums')}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z" />
              </svg>
              Albums
            </a>
          </li>
          <li>
            <a href="/artists" class:active={$page.url.pathname.startsWith('/artists')}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
              </svg>
              Artists
            </a>
          </li>
        </ul>

        <div class="sidebar-footer">
          <button type="button" class="logout-btn" on:click={() => auth.logout()}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
              <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z" />
            </svg>
            Logout
          </button>
        </div>
      </nav>

      <!-- Main content -->
      <main class="content" style:padding-bottom="{contentPadding}">
        <slot />
      </main>
    {:else}
      <!-- Login page (no sidebar) -->
      <main class="content-full">
        <slot />
      </main>
    {/if}

    <!-- Player bar (fixed at bottom, above bottom tabs on mobile) -->
    {#if $isAuthenticated && showPlayerBar}
      <PlayerBar />
    {/if}

    <!-- Bottom tab bar (mobile only) -->
    {#if $isAuthenticated && $page.url.pathname !== '/login'}
      <BottomTabBar />
    {/if}
  </div>
{/if}

<style>
  .loading-screen {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100vh;
    gap: var(--spacing-lg);
    color: var(--color-text-secondary);
  }

  .spinner {
    width: 48px;
    height: 48px;
    border: 4px solid var(--color-bg-tertiary);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  .app {
    display: flex;
    min-height: 100vh;
  }

  .sidebar {
    width: 240px;
    height: 100vh;
    position: fixed;
    left: 0;
    top: 0;
    background-color: var(--color-bg-primary);
    border-right: 1px solid var(--color-border);
    display: flex;
    flex-direction: column;
    padding: var(--spacing-lg);
    /* iOS safe area support */
    padding-top: calc(var(--spacing-lg) + var(--safe-area-inset-top));
    padding-left: calc(var(--spacing-lg) + var(--safe-area-inset-left));
    z-index: 10;
  }

  .logo {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-md) 0;
    margin-bottom: var(--spacing-xl);
    color: var(--color-accent);
    font-weight: 600;
    font-size: 1.25rem;
  }

  .nav-links {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
    flex: 1;
  }

  .nav-links a {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-sm) var(--spacing-md);
    border-radius: var(--radius-md);
    color: var(--color-text-secondary);
    text-decoration: none;
    transition: all 0.2s;
    font-weight: 500;
  }

  .nav-links a:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-text-primary);
  }

  .nav-links a.active {
    background-color: var(--color-bg-tertiary);
    color: var(--color-accent);
  }

  .sidebar-footer {
    padding-top: var(--spacing-lg);
    border-top: 1px solid var(--color-border);
  }

  .logout-btn {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    width: 100%;
    padding: var(--spacing-sm) var(--spacing-md);
    background: none;
    border: none;
    border-radius: var(--radius-md);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all 0.2s;
    font-weight: 500;
  }

  .logout-btn:hover {
    background-color: var(--color-bg-hover);
    color: var(--color-error);
  }

  .content {
    margin-left: 240px;
    flex: 1;
    min-height: 100vh;
    padding: var(--spacing-xl);
  }

  .content-full {
    flex: 1;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  @media (max-width: 768px) {
    /* Hide sidebar on mobile - use bottom tab bar instead */
    .sidebar {
      display: none;
    }

    .content {
      margin-left: 0;
      width: 100%;
      min-width: 0;
      overflow-x: hidden;
      padding: var(--spacing-md); /* Reduce padding on mobile */
      padding-left: calc(var(--spacing-md) + var(--safe-area-inset-left));
      padding-right: calc(var(--spacing-md) + var(--safe-area-inset-right));
      padding-top: calc(var(--spacing-md) + var(--safe-area-inset-top));
      /* Bottom padding set dynamically via style binding (contentPadding reactive var) */
      /* This accounts for: bottom tabs (56px) + gap (8px) + player bar (90px if playing) + safe area */
    }

  }
</style>
