<script lang="ts">
  import type { MusicAlbum } from '@yaytsa/core';
  import AlbumCard from './AlbumCard.svelte';

  export let albums: MusicAlbum[] = [];
  export let loading: boolean = false;
  export let skeletonCount: number = 12;
</script>

<div class="album-grid-container">
  {#if loading}
    <div class="album-grid">
      {#each Array(skeletonCount) as _, i (i)}
        <div class="skeleton-card">
          <div class="skeleton-art"></div>
          <div class="skeleton-info">
            <div class="skeleton-title"></div>
            <div class="skeleton-artist"></div>
          </div>
        </div>
      {/each}
    </div>
  {:else if albums.length === 0}
    <div class="empty">
      <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z" />
      </svg>
      <p>No albums found</p>
    </div>
  {:else}
    <div class="album-grid">
      {#each albums as album (album.Id)}
        <AlbumCard {album} />
      {/each}
    </div>
  {/if}
</div>

<style>
  .album-grid-container {
    width: 100%;
    max-width: 100%;
    overflow-x: hidden;
  }

  .album-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(180px, 100%), 1fr));
    gap: var(--spacing-md);
    max-width: 100%;
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-md);
    padding: var(--spacing-xl);
    color: var(--color-text-secondary);
    text-align: center;
  }

  .empty svg {
    opacity: 0.5;
  }

  .skeleton-card {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
    padding: var(--spacing-md);
  }

  .skeleton-art {
    aspect-ratio: 1;
    border-radius: var(--radius-md);
    background: linear-gradient(
      90deg,
      var(--color-bg-tertiary) 25%,
      var(--color-bg-hover) 50%,
      var(--color-bg-tertiary) 75%
    );
    background-size: 200% 100%;
    animation: skeleton-pulse 1.5s ease-in-out infinite;
  }

  .skeleton-info {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .skeleton-title {
    height: 1rem;
    width: 80%;
    border-radius: var(--radius-sm);
    background: linear-gradient(
      90deg,
      var(--color-bg-tertiary) 25%,
      var(--color-bg-hover) 50%,
      var(--color-bg-tertiary) 75%
    );
    background-size: 200% 100%;
    animation: skeleton-pulse 1.5s ease-in-out infinite;
  }

  .skeleton-artist {
    height: 0.875rem;
    width: 60%;
    border-radius: var(--radius-sm);
    background: linear-gradient(
      90deg,
      var(--color-bg-tertiary) 25%,
      var(--color-bg-hover) 50%,
      var(--color-bg-tertiary) 75%
    );
    background-size: 200% 100%;
    animation: skeleton-pulse 1.5s ease-in-out infinite;
  }

  @keyframes skeleton-pulse {
    0% {
      background-position: 200% 0;
    }
    100% {
      background-position: -200% 0;
    }
  }

  /* Small phones: flexible minimum with tighter spacing */
  @media (max-width: 375px) {
    .album-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(140px, 100%), 1fr));
      gap: var(--spacing-xs);
    }
  }

  /* Medium phones: slightly larger minimum */
  @media (min-width: 376px) and (max-width: 768px) {
    .album-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(150px, 100%), 1fr));
      gap: var(--spacing-sm);
    }
  }

  /* Large screens: bigger cards */
  @media (min-width: 1400px) {
    .album-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(200px, 100%), 1fr));
    }
  }
</style>
