<script lang="ts">
  import type { HTMLInputAttributes } from 'svelte/elements';

  export let type: string = 'text';
  export let value: string = '';
  export let placeholder: string = '';
  export let error: string = '';
  export let required: boolean = false;
  export let id: string = '';
  export let name: string = '';
  export let label: string = '';
  export let autocomplete: HTMLInputAttributes['autocomplete'] = undefined;

  function handleInput(e: Event) {
    value = (e.target as HTMLInputElement).value;
  }
</script>

<div class="input-group">
  {#if label}
    <label for={id} class="label">{label}</label>
  {/if}
  <input
    {id}
    name={name || id}
    {type}
    {placeholder}
    {required}
    {value}
    {autocomplete}
    class:error={!!error}
    on:input={handleInput}
    on:change
    on:blur
  />
  {#if error}
    <span class="error-message">{error}</span>
  {/if}
</div>

<style>
  .input-group {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
  }

  .label {
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--color-text-secondary);
  }

  input {
    width: 100%;
    padding: var(--spacing-sm) var(--spacing-md);
    background-color: var(--color-bg-secondary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    color: var(--color-text-primary);
    font-size: 1rem;
    transition: border-color 0.2s;
  }

  input:focus {
    outline: none;
    border-color: var(--color-accent);
  }

  input.error {
    border-color: var(--color-error);
  }

  .error-message {
    font-size: 0.875rem;
    color: var(--color-error);
  }

  /* Mobile touch targets - minimum 44px height */
  @media (max-width: 768px) {
    input {
      padding: var(--spacing-md); /* Increase padding for 44px min-height */
      min-height: 44px;
    }
  }
</style>
