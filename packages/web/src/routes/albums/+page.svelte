<script lang="ts">
  export let data = {};
  export let params = {};

  import { onMount } from 'svelte';
  import { library, albums, isLoading } from '../../lib/stores/library.js';
  import { isAuthenticated } from '../../lib/stores/auth.js';
  import AlbumGrid from '../../lib/components/library/AlbumGrid.svelte';
  import { get } from 'svelte/store';

  type SortOption = 'SortName' | 'ProductionYear' | 'AlbumArtist' | 'DateCreated';

  const sortOptions: { value: SortOption; label: string }[] = [
    { value: 'SortName', label: 'Name' },
    { value: 'AlbumArtist', label: 'Artist' },
    { value: 'ProductionYear', label: 'Year' },
    { value: 'DateCreated', label: 'Date Added' },
  ];

  let currentSort: SortOption = 'SortName';

  async function loadWithSort(sortBy: SortOption) {
    if (!get(isAuthenticated)) {
      return;
    }
    try {
      await library.loadAlbums({ limit: 100, sortBy });
    } catch (error) {
      console.error('Failed to load albums:', error);
    }
  }

  function handleSortChange(event: Event) {
    const target = event.target as HTMLSelectElement;
    currentSort = target.value as SortOption;
    loadWithSort(currentSort);
  }

  onMount(() => {
    loadWithSort(currentSort);
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
        <p>Browse your music collection</p>
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
</style>
