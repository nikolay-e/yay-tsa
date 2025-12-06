<script lang="ts">
  import { onMount } from 'svelte';
  import { decode } from 'blurhash';

  export let src: string;
  export let srcset: string | undefined = undefined;
  export let alt: string;
  export let blurHash: string | undefined = undefined;
  export let width: number | undefined = undefined;
  export let height: number | undefined = undefined;
  export let loading: 'lazy' | 'eager' = 'lazy';
  export let className: string = '';

  let canvas: HTMLCanvasElement;
  let imageLoaded = false;
  let imageFailed = false;

  onMount(() => {
    if (blurHash && canvas) {
      try {
        const pixels = decode(blurHash, 40, 40);
        const ctx = canvas.getContext('2d');
        if (ctx) {
          const imageData = new ImageData(
            new Uint8ClampedArray(pixels),
            40,
            40
          );
          ctx.putImageData(imageData, 0, 0);
        }
      } catch (error) {
        console.warn('Failed to decode BlurHash:', error);
      }
    }
  });

  function handleImageLoad() {
    imageLoaded = true;
  }

  function handleImageError() {
    imageFailed = true;
  }
</script>

<div class="blur-hash-image {className}">
  {#if blurHash && !imageLoaded && !imageFailed}
    <canvas
      bind:this={canvas}
      width="40"
      height="40"
      class="blur-placeholder"
      aria-hidden="true"
    ></canvas>
  {/if}

  <img
    {src}
    {srcset}
    {alt}
    {width}
    {height}
    {loading}
    decoding="async"
    class="main-image"
    class:loaded={imageLoaded}
    class:failed={imageFailed}
    on:load={handleImageLoad}
    on:error={handleImageError}
  />
</div>

<style>
  .blur-hash-image {
    position: relative;
    overflow: hidden;
    background-color: var(--color-bg-secondary, #f0f0f0);
  }

  .blur-placeholder {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    filter: blur(50px);
    transform: scale(1.1);
    image-rendering: pixelated;
    opacity: 1;
    transition: opacity 300ms ease-out;
  }

  .main-image {
    width: 100%;
    height: auto;
    display: block;
    opacity: 0;
    transition: opacity 300ms ease-in-out;
  }

  .main-image.loaded {
    opacity: 1;
  }

  .main-image.failed {
    opacity: 0.3;
  }
</style>
