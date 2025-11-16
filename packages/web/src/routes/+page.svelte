<script lang="ts">
  import { onMount } from 'svelte';
  import { library, albums, isLoading } from '../lib/stores/library.js';
  import { isAuthenticated } from '../lib/stores/auth.js';
  import AlbumGrid from '../lib/components/library/AlbumGrid.svelte';
  import { get } from 'svelte/store';

  let isRandomFallback = false;

  onMount(async () => {
    if (!get(isAuthenticated)) {
      return;
    }
    try {
      const result = await library.loadRecentlyPlayedAlbums(24);
      isRandomFallback = result.isRandom;
    } catch (error) {
      console.error('Failed to load albums:', error);
    }
  });
</script>

<svelte:head>
  <title>Recent - Yaytsa</title>
</svelte:head>

<div class="recent">
  <header class="page-header">
    <h1>{isRandomFallback ? 'Discover' : 'Recently Played'}</h1>
    <p>
      {#if isRandomFallback}
        Random albums from your library
      {:else}
        Albums you played recently
      {/if}
    </p>
  </header>

  <AlbumGrid albums={$albums} loading={$isLoading} />
</div>

<style>
  .recent {
    max-width: 1600px;
    margin: 0 auto;
  }

  .page-header {
    margin-bottom: var(--spacing-xl);
  }

  .page-header h1 {
    font-size: 2rem;
    margin-bottom: var(--spacing-xs);
  }

  .page-header p {
    color: var(--color-text-secondary);
    font-size: 1rem;
  }
</style>
