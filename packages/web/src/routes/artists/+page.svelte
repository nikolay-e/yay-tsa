<script lang="ts">
  import { onMount } from 'svelte';
  import { library, artists, isLoading } from '../../lib/stores/library.js';
  import { isAuthenticated } from '../../lib/stores/auth.js';
  import ArtistGrid from '../../lib/components/library/ArtistGrid.svelte';
  import { get } from 'svelte/store';

  type SortOption = 'SortName' | 'DateCreated';

  const sortOptions: { value: SortOption; label: string }[] = [
    { value: 'SortName', label: 'Name' },
    { value: 'DateCreated', label: 'Recently Added' },
  ];

  let currentSort: SortOption = 'SortName';

  async function loadWithSort(sortBy: SortOption) {
    if (!get(isAuthenticated)) return;
    await library.loadArtists({ sortBy });
  }

  function handleSortChange(event: Event) {
    const select = event.target as HTMLSelectElement;
    currentSort = select.value as SortOption;
    loadWithSort(currentSort);
  }

  onMount(() => {
    loadWithSort(currentSort);
  });
</script>

<div class="artists-page">
  <header class="page-header">
    <div class="header-content">
      <h1>Artists</h1>
      <p class="subtitle">{$artists.length} artists in your library</p>
    </div>

    <div class="sort-controls">
      <label for="sort-select" class="visually-hidden">Sort by</label>
      <select id="sort-select" value={currentSort} on:change={handleSortChange}>
        {#each sortOptions as option}
          <option value={option.value}>{option.label}</option>
        {/each}
      </select>
    </div>
  </header>

  <ArtistGrid artists={$artists} loading={$isLoading} />
</div>

<style>
  .artists-page {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-lg);
  }

  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: var(--spacing-md);
  }

  .header-content h1 {
    font-size: 2rem;
    font-weight: 700;
    color: var(--color-text-primary);
    margin: 0;
  }

  .subtitle {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    margin-top: var(--spacing-xs);
  }

  .sort-controls {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
  }

  .sort-controls select {
    padding: var(--spacing-sm) var(--spacing-md);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background-color: var(--color-bg-secondary);
    color: var(--color-text-primary);
    font-size: 0.875rem;
    cursor: pointer;
  }

  .sort-controls select:focus {
    outline: none;
    border-color: var(--color-accent);
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }

  @media (max-width: 768px) {
    .page-header {
      flex-direction: column;
      align-items: stretch;
    }

    .header-content h1 {
      font-size: 1.5rem;
    }

    .sort-controls {
      width: 100%;
    }

    .sort-controls select {
      width: 100%;
    }
  }
</style>
