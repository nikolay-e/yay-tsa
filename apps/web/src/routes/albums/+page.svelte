<script lang="ts">
  export const data = {};
  export const params = {};

  import { onMount } from 'svelte';
  import {
    library,
    albums,
    isLoading,
    albumsTotal,
    hasMoreAlbums,
  } from '../../lib/features/library/library.store.js';
  import { isAuthenticated } from '../../lib/features/auth/auth.store.js';
  import AlbumGrid from '../../lib/features/library/components/AlbumGrid.svelte';
  import { get } from 'svelte/store';
  import { createLogger } from '@yaytsa/core';

  const log = createLogger('Albums');

  type SortOption = 'SortName' | 'ProductionYear' | 'AlbumArtist';

  const sortOptions: { value: SortOption; label: string }[] = [
    { value: 'SortName', label: 'Name' },
    { value: 'AlbumArtist', label: 'Artist' },
    { value: 'ProductionYear', label: 'Year' },  // Falls back to name if year missing
  ];

  let currentSort: SortOption = 'SortName';

  async function loadWithSort(sortBy: SortOption, append: boolean = false) {
    if (!get(isAuthenticated)) {
      return;
    }
    try {
      await library.loadAlbums({ sortBy, append });
    } catch (error) {
      log.error('Failed to load albums', error);
    }
  }

  function handleSortChange(event: Event) {
    const target = event.target as HTMLSelectElement;
    currentSort = target.value as SortOption;
    loadWithSort(currentSort, false);
  }

  async function loadMore() {
    if ($isLoading || !$hasMoreAlbums) return;
    try {
      await loadWithSort(currentSort, true);
    } catch (error) {
      log.error('Failed to load more albums', error);
    }
  }

  onMount(() => {
    loadWithSort(currentSort, false);
  });
</script>

<svelte:head>
  <title>Albums - Yaytsa</title>
</svelte:head>

<div class="albums-page">
  <header class="page-header">
    <div class="header-content">
      <div class="header-text">
        <h1>Albums</h1>
        <p>
          {#if $albums.length > 0}
            {$albumsTotal || $albums.length} albums in your collection
          {:else}
            Browse your music collection
          {/if}
        </p>
      </div>
      <div class="sort-controls">
        <label for="sort-select">Sort by:</label>
        <select id="sort-select" value={currentSort} on:change={handleSortChange}>
          {#each sortOptions as option}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
      </div>
    </div>
  </header>

  <AlbumGrid albums={$albums} loading={$isLoading} />

  {#if $hasMoreAlbums}
    <div class="load-more">
      <button type="button" on:click={loadMore} disabled={$isLoading} class="load-more-btn">
        {$isLoading ? 'Loading…' : 'Load more'}
      </button>
    </div>
  {/if}
</div>

<style>
  .albums-page {
    max-width: 1600px;
    margin: 0 auto;
  }

  .page-header {
    margin-bottom: var(--spacing-xl);
  }

  .header-content {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: var(--spacing-lg);
  }

  .header-text h1 {
    font-size: 2rem;
    margin-bottom: var(--spacing-xs);
  }

  .header-text p {
    color: var(--color-text-secondary);
    font-size: 1rem;
  }

  .sort-controls {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    flex-shrink: 0;
  }

  .sort-controls label {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  .sort-controls select {
    padding: var(--spacing-xs) var(--spacing-sm);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background-color: var(--color-bg-secondary);
    color: var(--color-text-primary);
    font-size: 0.875rem;
    cursor: pointer;
  }

  .sort-controls select:hover {
    border-color: var(--color-text-tertiary);
  }

  .sort-controls select:focus {
    outline: none;
    border-color: var(--color-accent);
    box-shadow: 0 0 0 2px rgba(var(--color-accent-rgb), 0.2);
  }

  @media (max-width: 768px) {
    .header-content {
      flex-direction: column;
      align-items: stretch;
    }

    .sort-controls {
      justify-content: flex-start;
    }
  }

  .load-more {
    display: flex;
    justify-content: center;
    margin-top: var(--spacing-lg);
  }

  .load-more-btn {
    padding: var(--spacing-sm) var(--spacing-lg);
    border-radius: var(--radius-md);
    border: 1px solid var(--color-border);
    background: var(--color-bg-secondary);
    color: var(--color-text-primary);
    cursor: pointer;
  }

  .load-more-btn:hover:not(:disabled) {
    background: var(--color-bg-hover);
  }
</style>
