<script lang="ts">
  import { onMount } from 'svelte';
  import { library, albums, isLoading } from '../../lib/stores/library.js';
  import AlbumGrid from '../../lib/components/library/AlbumGrid.svelte';

  onMount(async () => {
    try {
      await library.loadAlbums({ limit: 100 });
    } catch (error) {
      console.error('Failed to load albums:', error);
    }
  });
</script>

<svelte:head>
  <title>Albums - Yaytsa</title>
</svelte:head>

<div class="albums-page">
  <header class="page-header">
    <h1>Albums</h1>
    <p>Browse your music collection</p>
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

  .page-header h1 {
    font-size: 2rem;
    margin-bottom: var(--spacing-xs);
  }

  .page-header p {
    color: var(--color-text-secondary);
    font-size: 1rem;
  }
</style>
