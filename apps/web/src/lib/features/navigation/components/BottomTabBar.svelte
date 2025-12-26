<script lang="ts">
  import { page } from '$app/stores';
  import { derived } from 'svelte/store';
  import { hapticSelect } from '../../../shared/utils/haptics.js';
  import { auth } from '../../auth/auth.store.js';

  interface Tab {
    href: string;
    label: string;
    icon: string;
    paths: string[];
  }

  const tabs: Tab[] = [
    {
      href: '/',
      label: 'Home',
      icon: 'home',
      paths: ['/']
    },
    {
      href: '/albums',
      label: 'Albums',
      icon: 'albums',
      paths: ['/albums']
    },
    {
      href: '/artists',
      label: 'Artists',
      icon: 'artists',
      paths: ['/artists']
    }
  ];

  // Memoized derived stores for each tab - no function recreation on $page change
  const homeActive = derived(page, $page => $page.url.pathname === '/');
  const albumsActive = derived(page, $page => $page.url.pathname.startsWith('/albums'));
  const artistsActive = derived(page, $page => $page.url.pathname.startsWith('/artists'));

  function getTabActive(index: number): boolean {
    if (index === 0) return $homeActive;
    if (index === 1) return $albumsActive;
    return $artistsActive;
  }

  function handleTabClick() {
    hapticSelect();
  }

  function handleLogout() {
    hapticSelect();
    auth.logout();
  }
</script>

<nav class="bottom-tab-bar" aria-label="Main navigation">
  {#each tabs as tab, index}
    <a
      href={tab.href}
      class="tab"
      data-testid="nav-{tab.icon}"
      class:active={getTabActive(index)}
      aria-current={getTabActive(index) ? 'page' : undefined}
      on:click={handleTabClick}
    >
      {#if tab.icon === 'home'}
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z" />
        </svg>
      {:else if tab.icon === 'albums'}
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z" />
        </svg>
      {:else if tab.icon === 'artists'}
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
        </svg>
      {/if}
      <span class="tab-label">{tab.label}</span>
    </a>
  {/each}

  <button type="button" class="tab logout-tab" on:click={handleLogout}>
    <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z" />
    </svg>
    <span class="tab-label">Logout</span>
  </button>
</nav>

<style>
  .bottom-tab-bar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    display: flex;
    background-color: var(--color-bg-secondary);
    border-top: 1px solid var(--color-border);
    z-index: 50;
    padding-bottom: var(--safe-area-inset-bottom);
    height: calc(52px + var(--safe-area-inset-bottom));
  }

  .tab {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    min-height: 44px;
    padding: 4px 0;
    color: var(--color-text-secondary);
    text-decoration: none;
    transition: all 0.2s;
    -webkit-tap-highlight-color: transparent;
    /* Eliminate 300ms click delay on mobile */
    touch-action: manipulation;
  }

  .tab:active {
    transform: scale(0.95);
  }

  .tab.active {
    color: var(--color-accent);
    background-color: rgba(29, 185, 84, 0.1); /* Subtle green background */
  }

  .tab svg {
    flex-shrink: 0;
  }

  .tab-label {
    font-size: 0.6875rem;
    font-weight: 500;
    letter-spacing: 0.5px;
    text-transform: capitalize;
  }

  .tab.active .tab-label {
    font-weight: 700; /* Bolder font for active tab */
  }

  .logout-tab {
    border: none;
    background: none;
    cursor: pointer;
  }

  .logout-tab:hover {
    color: var(--color-error);
  }

  /* Desktop: hide bottom tab bar, use sidebar instead */
  @media (min-width: 769px) {
    .bottom-tab-bar {
      display: none;
    }
  }
</style>
