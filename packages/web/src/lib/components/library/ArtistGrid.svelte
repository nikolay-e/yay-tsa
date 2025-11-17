<script lang="ts">
  import type { MusicArtist } from '@yaytsa/core';
  import ArtistCard from './ArtistCard.svelte';

  export let artists: MusicArtist[] = [];
  export let loading: boolean = false;
  export let skeletonCount: number = 12;
</script>

<div class="artist-grid-container">
  {#if loading}
    <div class="artist-grid">
      {#each Array(skeletonCount) as _, i (i)}
        <div class="skeleton-card">
          <div class="skeleton-image"></div>
          <div class="skeleton-info">
            <div class="skeleton-name"></div>
            <div class="skeleton-count"></div>
          </div>
        </div>
      {/each}
    </div>
  {:else if artists.length === 0}
    <div class="empty">
      <svg
        width="64"
        height="64"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
      >
        <path
          d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
        />
      </svg>
      <p>No artists found</p>
    </div>
  {:else}
    <div class="artist-grid">
      {#each artists as artist (artist.Id)}
        <ArtistCard {artist} />
      {/each}
    </div>
  {/if}
</div>

<style>
  .artist-grid-container {
    width: 100%;
    max-width: 100%;
    overflow-x: hidden;
  }

  .artist-grid {
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
    align-items: center;
  }

  .skeleton-image {
    width: 100%;
    aspect-ratio: 1;
    border-radius: 50%;
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
    width: 100%;
    align-items: center;
  }

  .skeleton-name {
    height: 1rem;
    width: 70%;
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

  .skeleton-count {
    height: 0.875rem;
    width: 40%;
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

  @media (max-width: 375px) {
    .artist-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(140px, 100%), 1fr));
      gap: var(--spacing-xs);
    }
  }

  @media (min-width: 376px) and (max-width: 768px) {
    .artist-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(150px, 100%), 1fr));
      gap: var(--spacing-sm);
    }
  }

  @media (min-width: 1400px) {
    .artist-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(200px, 100%), 1fr));
    }
  }
</style>
