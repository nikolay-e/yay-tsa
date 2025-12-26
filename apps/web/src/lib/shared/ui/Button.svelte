<script lang="ts">
  export let variant: 'primary' | 'secondary' | 'ghost' = 'primary';
  export let size: 'sm' | 'md' | 'lg' = 'md';
  export let disabled = false;
  export let type: 'button' | 'submit' | 'reset' = 'button';
</script>

<button
  {type}
  {disabled}
  class="btn btn-{variant} btn-{size}"
  on:click
>
  <slot />
</button>

<style>
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    font-weight: 500;
    border-radius: var(--radius-md);
    transition: all 0.2s;
    cursor: pointer;
  }

  /* Disabled state inherited from app.css button:disabled */

  /* Sizes */
  .btn-sm {
    padding: var(--spacing-xs) var(--spacing-sm);
    font-size: 0.875rem;
  }

  .btn-md {
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: 1rem;
  }

  .btn-lg {
    padding: var(--spacing-md) var(--spacing-lg);
    font-size: 1.125rem;
  }

  /* Variants */
  .btn-primary {
    background-color: var(--color-accent);
    color: white;
  }

  .btn-primary:hover:not(:disabled) {
    background-color: var(--color-accent-hover);
  }

  .btn-secondary {
    background-color: var(--color-bg-secondary);
    color: var(--color-text-primary);
    border: 1px solid var(--color-border);
  }

  .btn-secondary:hover:not(:disabled) {
    background-color: var(--color-bg-hover);
  }

  .btn-ghost {
    background-color: transparent;
    color: var(--color-text-primary);
  }

  .btn-ghost:hover:not(:disabled) {
    background-color: var(--color-bg-hover);
  }
</style>
