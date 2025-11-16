<script lang="ts">
  export let data = {};
  export let params = {};

  import { searchService, searchAlbums, searchTracks, isSearching } from '../../lib/stores/search.js';
  import AlbumGrid from '../../lib/components/library/AlbumGrid.svelte';
  import TrackList from '../../lib/components/library/TrackList.svelte';
  import { NAVIGATION_TEST_IDS } from '$lib/test-ids';
  import { onDestroy } from 'svelte';

  let query = '';
  let activeTab: 'albums' | 'tracks' = 'albums';

  let debounceTimer: ReturnType<typeof setTimeout>;

  function handleSearch() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(async () => {
      await searchService.search(query);
    }, 300);
  }

  onDestroy(() => {
    clearTimeout(debounceTimer);
    searchService.clear();
  });
</script>

<svelte:head>
  <title>Search - Yaytsa</title>
</svelte:head>

<div class="search-page">
  <div class="search-header">
    <input
      id="search-query"
      name="q"
      type="search"
      bind:value={query}
      on:input={handleSearch}
      placeholder="Search for albums, artists, or tracks..."
      class="search-input"
      aria-label="Search for albums, artists, or tracks"
      data-testid={NAVIGATION_TEST_IDS.SEARCH_INPUT}
    />
  </div>

  {#if query.trim()}
    <div class="tabs" role="tablist">
      <button type="button" class:active={activeTab === 'albums'} on:click={() => (activeTab = 'albums')} role="tab" aria-selected={activeTab === 'albums'}>
        Albums ({$searchAlbums.length})
      </button>
      <button type="button" class:active={activeTab === 'tracks'} on:click={() => (activeTab = 'tracks')} role="tab" aria-selected={activeTab === 'tracks'}>
        Tracks ({$searchTracks.length})
      </button>
    </div>

    <div class="search-results">
      {#if activeTab === 'albums'}
        <AlbumGrid albums={$searchAlbums} loading={$isSearching} />
      {:else}
        <TrackList tracks={$searchTracks} showAlbum={true} />
      {/if}
    </div>
  {/if}
</div>

<style>
  .search-page {
    max-width: 1600px;
    margin: 0 auto;
  }

  .search-header {
    margin-bottom: var(--spacing-xl);
  }

  .search-input {
    width: 100%;
    max-width: 600px;
    padding: var(--spacing-md) var(--spacing-lg);
    font-size: 1.125rem;
    background-color: var(--color-bg-secondary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    color: var(--color-text-primary);
  }

  .search-input:focus {
    outline: none;
    border-color: var(--color-accent);
  }

  .tabs {
    display: flex;
    gap: var(--spacing-md);
    margin-bottom: var(--spacing-lg);
    border-bottom: 1px solid var(--color-border);
  }

  .tabs button {
    padding: var(--spacing-md) var(--spacing-lg);
    background: none;
    border: none;
    color: var(--color-text-secondary);
    font-weight: 500;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    transition: all 0.2s;
  }

  .tabs button:hover {
    color: var(--color-text-primary);
  }

  .tabs button.active {
    color: var(--color-accent);
    border-bottom-color: var(--color-accent);
  }
</style>
