<script lang="ts">
  /**
   * The components palette: every component the `gui-components` registry
   * describes, one click to add it to the canvas. Nothing here is a component
   * list of its own; a component an add-on registers appears automatically.
   */
  import Icon from '../../components/Icon.svelte';
  import { componentIcon } from '../../gui/registry';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio }: { studio: GuiStudioState } = $props();
  const components = $derived(studio.registry?.components ?? []);
</script>

<section class="palette" aria-label="Components">
  <h3>Add</h3>
  {#if components.length === 0}
    <p class="empty">{studio.registryError || 'This server does not describe GUI components.'}</p>
  {:else}
    <ul>
      {#each components as component (component.id)}
        <li>
          <button
            type="button"
            title={component.description}
            disabled={studio.readOnly}
            onclick={() => studio.addComponent(component)}
          >
            <Icon name={componentIcon(component)} size={16} />
            <span>{component.displayName}</span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .palette {
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    margin: 0 0 var(--space-2);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  ul {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
    gap: var(--space-1);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  button {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    min-width: 0;
    padding: var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
    transition:
      background var(--motion-fast) var(--ease-standard),
      border-color var(--motion-fast) var(--ease-standard);
  }
  button span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  button:hover:not(:disabled) {
    background: var(--bg-hover);
    border-color: var(--accent);
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .empty {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
</style>
