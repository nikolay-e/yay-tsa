<script lang="ts">
  import type { MusicAlbum } from '@yaytsa/core';
  import { getAlbumArtUrl, getAlbumArtSrcSet } from '../../../shared/utils/image.js';
  import { player } from '../../player/player.store.js';
  import { library } from '../library.store.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';
  import { logger } from '../../../shared/utils/logger.js';
  import { LIBRARY_TEST_IDS } from '$lib/shared/test-ids';

  export let album: MusicAlbum;

  $: albumArtUrl = getAlbumArtUrl(album.Id, 'small', album.ImageTags?.Primary);
  $: albumArtSrcSet = getAlbumArtSrcSet(album.Id, album.ImageTags?.Primary, 'small');
  let imageError = false;

  // React to album ID change (primitive dependency) instead of object reference
  $: albumId = album.Id;
  $: {
    albumId; // Trigger on albumId change
    imageError = false;
  }

  function handleImageError() {
    imageError = true;
    logger.debug(`[AlbumCard] Failed to load image for album: ${album.Name} (${album.Id})`);
  }

  async function playAlbum(event: MouseEvent) {
    event.preventDefault();
    hapticSelect();
    try {
      const tracks = await library.getAlbumTracks(album.Id);
      if (tracks.length > 0) {
        player.playAlbum(tracks);
      }
    } catch (error) {
      logger.error('Failed to play album:', error);
    }
  }
</script>

<a href="/albums/{album.Id}" class="album-card" data-testid="album-card">
  <div class="album-art-container">
    {#if !imageError && albumArtUrl}
      <img
        src={albumArtUrl}
        srcset={albumArtSrcSet}
        alt={album.Name}
        class="album-art"
        data-testid="album-cover"
        width="300"
        height="300"
        loading="lazy"
        decoding="async"
        on:error={handleImageError}
      />
    {:else}
      <div class="album-art-placeholder">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z" />
        </svg>
      </div>
    {/if}
    <div class="play-overlay">
      <button type="button" class="play-button" on:click={playAlbum} aria-label="Play {album.Name}">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M8 5v14l11-7z" />
        </svg>
      </button>
    </div>
  </div>

  <div class="album-info">
    <h3 class="album-name" data-testid={LIBRARY_TEST_IDS.ALBUM_TITLE}>{album.Name}</h3>
    <p class="album-artist">{album.Artists?.[0] || 'Various Artists'}</p>
    {#if album.ProductionYear}
      <p class="album-year">{album.ProductionYear}</p>
    {/if}
  </div>
</a>

<style>
  .album-card {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
    padding: var(--spacing-md);
    border-radius: var(--radius-md);
    transition: background-color 0.2s;
    text-decoration: none;
    color: inherit;
    /* Eliminate 300ms click delay on mobile */
    touch-action: manipulation;
  }

  .album-card:hover {
    background-color: var(--color-bg-hover);
  }

  .album-art-container {
    position: relative;
    aspect-ratio: 1;
    border-radius: var(--radius-md);
    overflow: hidden;
    background-color: var(--color-bg-tertiary);
  }

  .album-art {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .album-art-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--color-text-tertiary);
    background-color: var(--color-bg-tertiary);
  }

  .play-overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: rgba(0, 0, 0, 0.6);
    opacity: 0;
    transition: opacity 0.2s;
  }

  .album-card:hover .play-overlay {
    opacity: 1;
  }

  .play-button {
    width: 64px;
    height: 64px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: var(--color-accent);
    border: none;
    border-radius: 50%;
    color: white;
    cursor: pointer;
    transition: all 0.2s;
    transform: scale(0.9);
    /* Eliminate 300ms click delay on mobile */
    touch-action: manipulation;
  }

  .album-card:hover .play-button {
    transform: scale(1);
  }

  .play-button:hover {
    background-color: var(--color-accent-hover);
    transform: scale(1.1);
  }

  .album-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .album-name {
    font-size: 1rem;
    font-weight: 500;
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .album-artist {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .album-year {
    font-size: 0.75rem;
    color: var(--color-text-tertiary);
  }

  /* Mobile: always show play button with lighter overlay */
  @media (max-width: 768px) {
    .play-overlay {
      opacity: 0.8; /* Always visible on mobile */
      background-color: rgba(0, 0, 0, 0.4); /* Lighter background */
    }

    .play-button {
      width: 48px; /* Slightly smaller on mobile */
      height: 48px;
      transform: scale(1); /* Always full size */
    }

    .play-button:active {
      transform: scale(0.95);
    }

    .album-name {
      font-size: 0.9375rem; /* Slightly smaller for mobile */
    }
  }
</style>
