<script lang="ts">
  import { page } from '$app/stores';
  import { library } from '../../../lib/features/library/stores/library.store.js';
  import { player } from '../../../lib/features/player/stores/player.store.js';
  import { getArtistImageUrl } from '../../../lib/shared/utils/image.js';
  import { hapticSelect } from '../../../lib/shared/utils/haptics.js';
  import AlbumGrid from '../../../lib/features/library/components/AlbumGrid.svelte';
  import type { MusicArtist, MusicAlbum } from '@yaytsa/core';

  let artist: MusicArtist | null = null;
  let albums: MusicAlbum[] = [];
  let loading = true;
  let error: string | null = null;
  let imageError = false;

  $: artistId = $page.params.id;

  // Reactive data loading - re-runs when artistId changes
  $: if (artistId) {
    void loadArtistData();
  }

  async function loadArtistData() {
    if (!artistId) {
      error = 'Artist ID is required';
      loading = false;
      return;
    }

    loading = true;
    error = null;

    try {
      const itemsService = library.getService();
      if (!itemsService) {
        throw new Error('Not authenticated');
      }

      const [artistData, artistAlbums] = await Promise.all([
        itemsService.getItem(artistId) as Promise<MusicArtist>,
        library.loadArtistAlbums(artistId),
      ]);

      artist = artistData;
      albums = artistAlbums;
    } catch (err) {
      error = (err as Error).message;
    } finally {
      loading = false;
    }
  }

  async function playAllAlbums() {
    hapticSelect();
    if (albums.length === 0) return;

    const trackResults = await Promise.allSettled(
      albums.map(album => library.getAlbumTracks(album.Id))
    );
    const allTracks = trackResults.flatMap(result =>
      result.status === 'fulfilled' ? result.value : []
    );

    if (allTracks.length > 0) {
      player.playAlbum(allTracks);
    }
  }

  async function shuffleAllAlbums() {
    hapticSelect();
    if (albums.length === 0) return;

    const trackResults = await Promise.allSettled(
      albums.map(album => library.getAlbumTracks(album.Id))
    );
    const allTracks = trackResults.flatMap(result =>
      result.status === 'fulfilled' ? result.value : []
    );

    if (allTracks.length > 0) {
      player.setShuffle(true);
      player.playAlbum(allTracks);
    }
  }

  function handleImageError() {
    imageError = true;
  }
</script>

<div class="artist-detail-page">
  {#if loading}
    <div class="loading">Loading artist...</div>
  {:else if error}
    <div class="error">Error: {error}</div>
  {:else if artist}
    <header class="artist-header">
      <div class="artist-image-container">
        {#if !imageError && artist.ImageTags?.Primary}
          <img
            src={getArtistImageUrl(artist.Id, 'large', artist.ImageTags?.Primary)}
            alt={artist.Name}
            class="artist-image"
            width="600"
            height="600"
            on:error={handleImageError}
          />
        {:else}
          <div class="artist-image-placeholder">
            <svg
              width="120"
              height="120"
              viewBox="0 0 24 24"
              fill="currentColor"
              aria-hidden="true"
            >
              <path
                d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
              />
            </svg>
          </div>
        {/if}
      </div>

      <div class="artist-info">
        <span class="type-badge">Artist</span>
        <h1 class="artist-name">{artist.Name}</h1>

        <div class="metadata">
          <span>{albums.length} {albums.length === 1 ? 'album' : 'albums'}</span>
          {#if artist.Genres && artist.Genres.length > 0}
            <span class="separator">•</span>
            <span>{artist.Genres.slice(0, 3).join(', ')}</span>
          {/if}
        </div>

        {#if artist.Overview}
          <p class="overview">{artist.Overview}</p>
        {/if}

        <div class="actions">
          <button type="button" class="play-btn" on:click={playAllAlbums} disabled={albums.length === 0}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5v14l11-7z" />
            </svg>
            Play All
          </button>
          <button type="button" class="shuffle-btn" on:click={shuffleAllAlbums} disabled={albums.length === 0}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
              <path
                d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
              />
            </svg>
            Shuffle
          </button>
        </div>
      </div>
    </header>

    <section class="albums-section">
      <h2>Albums</h2>
      <AlbumGrid {albums} loading={false} />
    </section>
  {/if}
</div>

<style>
  .artist-detail-page {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xl);
  }

  .loading,
  .error {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--spacing-xl);
    color: var(--color-text-secondary);
  }

  .error {
    color: var(--color-error);
  }

  .artist-header {
    display: grid;
    grid-template-columns: 300px 1fr;
    gap: var(--spacing-xl);
    align-items: start;
  }

  .artist-image-container {
    width: 300px;
    height: 300px;
    border-radius: 50%;
    overflow: hidden;
    background-color: var(--color-bg-tertiary);
    flex-shrink: 0;
  }

  .artist-image {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .artist-image-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--color-text-tertiary);
  }

  .artist-info {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
  }

  .type-badge {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--color-text-secondary);
    letter-spacing: 0.05em;
  }

  .artist-name {
    font-size: 3rem;
    font-weight: 700;
    color: var(--color-text-primary);
    margin: 0;
    line-height: 1.1;
  }

  .metadata {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    font-size: 0.875rem;
    color: var(--color-text-secondary);
  }

  .separator {
    opacity: 0.5;
  }

  .overview {
    font-size: 0.9375rem;
    color: var(--color-text-secondary);
    line-height: 1.5;
    margin-top: var(--spacing-sm);
  }

  .actions {
    display: flex;
    gap: var(--spacing-md);
    margin-top: var(--spacing-md);
  }

  .play-btn,
  .shuffle-btn {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-lg);
    border-radius: var(--radius-lg);
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
    touch-action: manipulation;
  }

  .play-btn {
    background-color: var(--color-accent);
    color: white;
    border: none;
  }

  .play-btn:hover:not(:disabled) {
    background-color: var(--color-accent-hover);
    transform: scale(1.05);
  }

  .play-btn:disabled,
  .shuffle-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .shuffle-btn {
    background-color: transparent;
    color: var(--color-text-primary);
    border: 1px solid var(--color-border);
  }

  .shuffle-btn:hover:not(:disabled) {
    background-color: var(--color-bg-hover);
    transform: scale(1.05);
  }

  .albums-section {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .albums-section h2 {
    font-size: 1.5rem;
    font-weight: 600;
    color: var(--color-text-primary);
    margin: 0;
  }

  @media (max-width: 768px) {
    .artist-header {
      grid-template-columns: 1fr;
      gap: var(--spacing-lg);
    }

    .artist-image-container {
      width: 240px;
      height: 240px;
      margin: 0 auto;
    }

    .artist-info {
      text-align: center;
    }

    .artist-name {
      font-size: 1.75rem;
    }

    .metadata {
      justify-content: center;
    }

    .actions {
      justify-content: center;
    }
  }

  @media (max-width: 375px) {
    .artist-image-container {
      width: 200px;
      height: 200px;
    }

    .artist-name {
      font-size: 1.5rem;
    }

    .actions {
      flex-direction: column;
    }

    .play-btn,
    .shuffle-btn {
      width: 100%;
      justify-content: center;
    }
  }
</style>
