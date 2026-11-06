<script lang="ts">
  /**
   * The Logic section of the Design tab's inspector (logic.md section 7.4). For a button, an item or a list it offers "Add
   * click logic"; for a layer that has rules it says how many, opens them in the Logic tab, and lists what the compiler
   * says about them, each with the step path it was reported at, so a diagnostic is drawn on the layer as well as on the node.
   */
  import Icon from '../../../components/Icon.svelte';
  import type { GuiLayer } from '../../../gui/model';
  import type { LinkageState } from '../../../logic/linkage/linkage.svelte';

  let { links, layer, disabled = false }: { links: LinkageState; layer: GuiLayer; disabled?: boolean } = $props();

  const count = $derived(links.ruleCount(layer.id));
  const flags = $derived(links.flagsFor(layer.id));
  const clickable = $derived(links.clickable(layer));
</script>

{#if clickable || count > 0}
  <section class="group" data-testid="layer-logic">
    <h3><Icon name="zap" size={12} /> Logic</h3>
    {#if count > 0}
      <p class="line">
        <span data-testid="layer-logic-count">{count} rule{count === 1 ? '' : 's'} answer{count === 1 ? 's' : ''} clicks on this layer.</span>
      </p>
      <button type="button" class="btn" onclick={() => links.openRules(layer.id)} data-testid="layer-open-logic">
        <Icon name="workflow" size={14} /> Open in Logic
      </button>
    {/if}
    {#if clickable}
      <button
        type="button"
        class="btn"
        class:primary={count === 0}
        disabled={disabled || !links.canAdd}
        title={links.canAdd ? 'Add a rule that runs when this layer is clicked' : 'The Logic tab needs the server\'s logic API and vocabulary'}
        onclick={() => links.addClickLogic(layer.id)}
        data-testid="add-click-logic"
      >
        <Icon name="zap" size={14} /> {count === 0 ? 'Add click logic' : 'Add another click rule'}
      </button>
    {/if}
    {#each flags as flag, index (index)}
      <p class="flag {flag.severity}" data-testid="layer-logic-flag" title={flag.path ? `at ${flag.path}` : undefined}>
        <Icon name={flag.severity === 'info' ? 'info' : 'triangle-alert'} size={12} />
        <span>{flag.message}</span>{#if flag.path}<code>{flag.path}</code>{/if}
      </p>
    {/each}
  </section>
{/if}

<style>
  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    margin: 0;
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .line {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: inherit;
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .btn:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .btn.primary {
    background: var(--accent);
    border-color: transparent;
    color: var(--accent-ink);
    font-weight: 600;
  }
  .btn:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .flag {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-1);
    margin: 0;
    font-size: var(--text-xs);
    min-width: 0;
  }
  .flag span {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .flag.error {
    color: var(--error);
  }
  .flag.warn {
    color: var(--warn);
  }
  .flag.info {
    color: var(--muted);
  }
  code {
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
</style>
