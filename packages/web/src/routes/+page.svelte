<script lang="ts">
  import { onMount } from 'svelte';
  import { library, albums, isLoading } from '../lib/stores/library.js';
  import AlbumGrid from '../lib/components/library/AlbumGrid.svelte';

  onMount(async () => {
    try {
      await library.loadRecentAlbums(24);
    } catch (error) {
      console.error('Failed to load albums:', error);
    }
  });
</script>

<svelte:head>
  <title>Home - Yaytsa</title>
</svelte:head>

<div class="home">
  <header class="page-header">
    <h1>Recent Albums</h1>
    <p>Recently added music from your library</p>
  </header>

  <AlbumGrid albums={$albums} loading={$isLoading} />
</div>

<style>
  .home {
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
