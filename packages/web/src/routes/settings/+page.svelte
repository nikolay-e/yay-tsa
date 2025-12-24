<script lang="ts">
  import PasskeySettings from '../../lib/components/settings/PasskeySettings.svelte';
</script>

<div class="settings-page">
  <div class="page-header">
    <h1>Settings</h1>
  </div>

  <div class="settings-sections">
    <section class="settings-section">
      <PasskeySettings />
    </section>
  </div>
</div>

<style>
  .settings-page {
    max-width: 800px;
    margin: 0 auto;
    padding: var(--spacing-lg);
  }

  .page-header {
    margin-bottom: var(--spacing-xl);
  }

  .page-header h1 {
    margin: 0;
    font-size: 2rem;
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .settings-sections {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xl);
  }

  .settings-section {
    padding: var(--spacing-lg);
    background-color: var(--color-bg-secondary);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
  }

  @media (max-width: 768px) {
    .settings-page {
      padding: var(--spacing-md);
    }

    .page-header h1 {
      font-size: 1.5rem;
    }

    .settings-section {
      padding: var(--spacing-md);
    }
  }
</style>
