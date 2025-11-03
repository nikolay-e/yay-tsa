<script lang="ts">
  import { onMount } from 'svelte';
  import { page } from '$app/stores';
  import { library } from '../../../lib/stores/library.js';
  import { player } from '../../../lib/stores/player.js';
  import { getAlbumArtUrl } from '../../../lib/utils/image.js';
  import TrackList from '../../../lib/components/library/TrackList.svelte';
  import type { MusicAlbum, AudioItem } from '@jellyfin-mini/core';

  let album: MusicAlbum | null = null;
  let tracks: AudioItem[] = [];
  let loading = true;

  $: albumId = $page.params.id;

  onMount(async () => {
    await loadAlbumData();
  });

  async function loadAlbumData() {
    loading = true;
    try {
      if (!albumId) {
        console.error('No album ID provided');
        loading = false;
        return;
      }

      const itemsService = library.getService();
      if (itemsService) {
        // Fetch album details and tracks concurrently for better performance
        const [albumData, albumTracks] = await Promise.all([
          itemsService.getItem(albumId) as Promise<MusicAlbum>,
          library.loadAlbumTracks(albumId)
        ]);
        album = albumData;
        tracks = albumTracks;
      }
    } catch (error) {
      console.error('Failed to load album:', error);
    } finally {
      loading = false;
    }
  }

  function playAlbum() {
    if (tracks.length > 0) {
      player.playAlbum(tracks);
    }
  }

  function shuffleAlbum() {
    if (tracks.length > 0) {
      player.toggleShuffle();
      player.playAlbum(tracks);
    }
  }
</script>

<svelte:head>
  <title>{album?.Name || 'Album'} - Jellyfin Mini Player</title>
</svelte:head>

<div class="album-detail">
  {#if loading}
    <div class="loading">Loading album...</div>
  {:else if album}
    <div class="album-header">
      <img
        src={getAlbumArtUrl(album.Id, 'large')}
        alt={album.Name}
        class="album-art"
      />

      <div class="album-info">
        <p class="album-type">Album</p>
        <h1 class="album-name">{album.Name}</h1>
        <p class="album-artist">{album.Artists?.[0] || 'Unknown Artist'}</p>

        <div class="album-meta">
          {#if album.ProductionYear}
            <span>{album.ProductionYear}</span>
          {/if}
          <span>{tracks.length} tracks</span>
        </div>

        <div class="album-actions">
          <button type="button" class="btn-play" on:click={playAlbum}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5v14l11-7z" />
            </svg>
            Play
          </button>

          <button type="button" class="btn-shuffle" on:click={shuffleAlbum}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5" />
            </svg>
            Shuffle
          </button>
        </div>
      </div>
    </div>

    <div class="album-tracks">
      <TrackList {tracks} />
    </div>
  {:else}
    <div class="error">Album not found</div>
  {/if}
</div>

<style>
  .album-detail {
    max-width: 1200px;
    margin: 0 auto;
  }

  .loading,
  .error {
    padding: var(--spacing-xl);
    text-align: center;
    color: var(--color-text-secondary);
  }

  .album-header {
    display: grid;
    grid-template-columns: 300px 1fr;
    gap: var(--spacing-xl);
    margin-bottom: var(--spacing-xl);
  }

  .album-art {
    width: 100%;
    aspect-ratio: 1;
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg);
  }

  .album-info {
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    gap: var(--spacing-sm);
  }

  .album-type {
    font-size: 0.875rem;
    font-weight: 500;
    text-transform: uppercase;
    color: var(--color-text-secondary);
  }

  .album-name {
    font-size: 3rem;
    font-weight: 700;
    line-height: 1.1;
    margin: 0;
  }

  .album-artist {
    font-size: 1.25rem;
    color: var(--color-text-secondary);
  }

  .album-meta {
    display: flex;
    gap: var(--spacing-md);
    font-size: 0.875rem;
    color: var(--color-text-tertiary);
  }

  .album-meta span:not(:last-child)::after {
    content: '•';
    margin-left: var(--spacing-md);
  }

  .album-actions {
    display: flex;
    gap: var(--spacing-md);
    margin-top: var(--spacing-md);
  }

  .btn-play {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-md) var(--spacing-xl);
    background-color: var(--color-accent);
    color: white;
    border: none;
    border-radius: var(--radius-lg);
    font-size: 1.125rem;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
  }

  .btn-play:hover {
    background-color: var(--color-accent-hover);
    transform: scale(1.05);
  }

  .btn-shuffle {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-md) var(--spacing-lg);
    background-color: transparent;
    color: var(--color-text-primary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    font-size: 1rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
  }

  .btn-shuffle:hover {
    background-color: var(--color-bg-hover);
    border-color: var(--color-text-primary);
  }

  .album-tracks {
    margin-top: var(--spacing-xl);
  }

  /* Small phones: compact layout */
  @media (max-width: 375px) {
    .album-header {
      grid-template-columns: 1fr;
      gap: var(--spacing-md);
    }

    .album-art {
      max-width: 200px;
      margin: 0 auto;
    }

    .album-name {
      font-size: 1.5rem;
    }

    .album-artist {
      font-size: 1rem;
    }

    .album-actions {
      flex-direction: row;
      gap: var(--spacing-sm);
    }

    .btn-play,
    .btn-shuffle {
      flex: 1;
      justify-content: center;
      padding: var(--spacing-md);
    }
  }

  /* Medium phones: moderate sizing */
  @media (min-width: 376px) and (max-width: 768px) {
    .album-header {
      grid-template-columns: 1fr;
      gap: var(--spacing-lg);
    }

    .album-art {
      max-width: 240px;
      margin: 0 auto;
    }

    .album-name {
      font-size: 1.75rem;
    }

    .album-actions {
      flex-direction: row;
      gap: var(--spacing-sm);
    }

    .btn-play,
    .btn-shuffle {
      flex: 1;
      justify-content: center;
    }
  }
</style>
