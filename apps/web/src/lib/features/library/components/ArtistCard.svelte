<script lang="ts">
  import type { MusicArtist } from '@yaytsa/core';
  import { getArtistImageUrl, getArtistImageSrcSet } from '../../../shared/utils/image.js';
  import { hapticSelect } from '../../../shared/utils/haptics.js';
  import { logger } from '../../../shared/utils/logger.js';

  export let artist: MusicArtist;

  $: artistImageUrl = getArtistImageUrl(artist.Id, 'small', artist.ImageTags?.Primary);
  $: artistImageSrcSet = getArtistImageSrcSet(artist.Id, artist.ImageTags?.Primary, 'small');
  let imageError = false;

  $: artistId = artist.Id;
  $: {
    artistId;
    imageError = false;
  }

  function handleImageError() {
    imageError = true;
    logger.debug(`[ArtistCard] Failed to load image for artist: ${artist.Name} (${artist.Id})`);
  }

  function handleClick() {
    hapticSelect();
  }
</script>

<a
  href="/artists/{artist.Id}"
  class="artist-card"
  data-testid="artist-card"
  on:click={handleClick}
>
  <div class="artist-image-container">
    {#if !imageError && artistImageUrl}
      <img
        src={artistImageUrl}
        srcset={artistImageSrcSet}
        alt={artist.Name}
        class="artist-image"
        data-testid="artist-image"
        width="300"
        height="300"
        loading="lazy"
        decoding="async"
        on:error={handleImageError}
      />
    {:else}
      <div class="artist-image-placeholder">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path
            d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
          />
        </svg>
      </div>
    {/if}
  </div>

  <div class="artist-info">
    <h3 class="artist-name" data-testid="artist-name">{artist.Name}</h3>
    <p class="artist-albums">
      {artist.ChildCount || 0}
      {artist.ChildCount === 1 ? 'album' : 'albums'}
    </p>
  </div>
</a>

<style>
  .artist-card {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
    padding: var(--spacing-md);
    border-radius: var(--radius-md);
    transition: background-color 0.2s;
    text-decoration: none;
    color: inherit;
    touch-action: manipulation;
  }

  .artist-card:hover {
    background-color: var(--color-bg-hover);
  }

  .artist-image-container {
    position: relative;
    aspect-ratio: 1;
    border-radius: 50%;
    overflow: hidden;
    background-color: var(--color-bg-tertiary);
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
    background-color: var(--color-bg-tertiary);
  }

  .artist-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: center;
  }

  .artist-name {
    font-size: 1rem;
    font-weight: 500;
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .artist-albums {
    font-size: 0.875rem;
    color: var(--color-text-secondary);
  }

  @media (max-width: 768px) {
    .artist-name {
      font-size: 0.9375rem;
    }
  }
</style>
