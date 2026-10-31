<script lang="ts">
  /**
   * The Logic tab of the GUI studio (logic.md §7.1): the same document as the Design tab, drawn as a node
   * graph. It has no model of its own. What it edits is the studio's document, through the studio's history,
   * so Save, Ctrl+Z and the dirty marker are shared with Design, and what it saves goes through `logic.edit`.
   */
  import Icon from '../../components/Icon.svelte';
  import type { IconName } from '../../components/icons';
  import type { LogicCanvasState } from '../../logic/canvas.svelte';
  import { zoomLabel } from '../../logic/viewport';
  import { revealInAdvanced, session } from '../../state.svelte';
  import type { ResourceRef } from '../../api';
  import LogicCanvas from './LogicCanvas.svelte';

  let { logic, resource, active = true }: { logic: LogicCanvasState; resource: ResourceRef; active?: boolean } = $props();

  $effect(() => {
    void logic.connect(session.channel);
  });

  const selected = $derived(logic.selected);
  const description = $derived.by(() => {
    if (selected) return `${logic.nameOf(selected)} · ${selected.id}`;
    const rules = logic.graph.nodes.filter((node) => node.kind === 'trigger').length;
    const flows = logic.graph.nodes.filter((node) => node.kind === 'entry').length;
    const steps = logic.graph.nodes.length - rules - flows;
    return `${rules} rule${rules === 1 ? '' : 's'}, ${steps} step${steps === 1 ? '' : 's'}${flows ? `, ${flows} flow${flows === 1 ? '' : 's'}` : ''}`;
  });
  const hint = $derived(
    logic.message ||
      (logic.editable
        ? 'Drag a node by its header · drag from a port to add or connect · / opens the palette · F frames'
        : ''),
  );
</script>

{#snippet tool(icon: IconName, label: string, shortcut: string, onclick: () => void, disabled = false)}
  <button type="button" class="tool" title="{label} ({shortcut})" aria-label={label} {disabled} {onclick}>
    <Icon name={icon} size={16} />
  </button>
{/snippet}

<section class="logic" aria-label="Logic" data-testid="logic-tab">
  {#if logic.status === 'unavailable'}
    <div class="state" data-testid="logic-unavailable">
      <Icon name="workflow" size={28} />
      <h3>The Logic tab needs the logic API</h3>
      <p>
        This connection does not advertise the <code>logic</code> capability (an older Materione plugin, or the in-browser demo).
        Update the plugin to draw and edit this GUI's rules as a graph; the rules in the file are untouched.
      </p>
    </div>
  {:else}
    <div class="tools" role="toolbar" aria-label="Logic tools">
      <button type="button" class="add" disabled={!logic.editable} onclick={() => logic.openPalette()} title="Add a node (/)">
        <Icon name="plus" size={14} /> Add node <kbd>/</kbd>
      </button>
      {@render tool('workflow', 'New named flow', 'toolbar', () => logic.addFlow(), !logic.editable)}
      <span class="rule"></span>
      {@render tool('trash-2', 'Delete the selected node', 'Delete', () => logic.remove(), !logic.editable || !logic.selection)}
      <span class="rule"></span>
      {@render tool('zoom-out', 'Zoom out', '-', () => logic.zoomStep(-1))}
      <button type="button" class="zoom" title="Actual size (1)" onclick={() => logic.actualSize()} data-logic-zoom>{zoomLabel(logic.view.zoom)}</button>
      {@render tool('zoom-in', 'Zoom in', '+', () => logic.zoomStep(1))}
      {@render tool('scan', 'Frame the selection, or everything', 'F', () => logic.frame())}
      <span class="rule"></span>
      {@render tool('undo-2', logic.gui.undoLabel ? `Undo ${logic.gui.undoLabel}` : 'Undo', 'Ctrl+Z', () => logic.undo(), !logic.gui.canUndo || logic.gui.readOnly)}
      {@render tool('redo-2', logic.gui.redoLabel ? `Redo ${logic.gui.redoLabel}` : 'Redo', 'Ctrl+Shift+Z', () => logic.redo(), !logic.gui.canRedo || logic.gui.readOnly)}
    </div>

    {#if logic.status === 'failed'}
      <p class="banner error" role="alert">The server's vocabulary could not be read: {logic.error}</p>
    {/if}
    {#if logic.problems.length > 0}
      <p class="banner error" role="alert" data-testid="logic-problems">
        This file's logic cannot be drawn faithfully ({logic.problems[0]}), so it is shown but not edited here;
        <button type="button" class="link" onclick={() => revealInAdvanced(resource)}>edit it in Files</button>.
      </p>
    {:else if logic.gui.readOnly}
      <p class="banner warn" role="status">This file is read-only in the studio, so its logic is too.</p>
    {/if}

    <LogicCanvas canvas={logic} {active} />

    <footer class="statusbar">
      <span class="sel" data-testid="logic-selection">{description}</span>
      <span class="spacer"></span>
      <span class="hintline" class:refusal={logic.message !== '' && logic.messageKind === 'refusal'} role="status" data-testid="logic-hint">{hint}</span>
    </footer>
  {/if}
</section>

<style>
  .logic {
    display: flex;
    flex: 1 1 0;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
  }
  .tools {
    display: flex;
    flex: none;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-panel);
    min-width: 0;
  }
  .rule {
    align-self: stretch;
    width: 1px;
    margin: var(--space-1);
    background: var(--border);
  }
  .tool {
    display: inline-grid;
    place-items: center;
    width: 30px;
    height: 28px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
    transition:
      background var(--motion-fast) var(--ease-standard),
      color var(--motion-fast) var(--ease-standard);
  }
  .tool:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .tool:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .add {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    height: 28px;
    padding: 0 var(--space-3);
    background: var(--accent);
    border: 0;
    border-radius: var(--radius);
    color: var(--accent-ink);
    font: 600 var(--text-sm) var(--font-sans);
    cursor: pointer;
  }
  .add:disabled {
    background: none;
    border: 1px solid var(--border);
    color: var(--faint);
    cursor: default;
  }
  kbd {
    padding: 0 var(--space-1);
    border: 1px solid currentColor;
    border-radius: var(--radius-sm);
    font: var(--text-xs) var(--mono);
    opacity: 0.8;
  }
  .zoom {
    min-width: 52px;
    height: 28px;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
    cursor: pointer;
  }
  .zoom:hover {
    border-color: var(--accent);
  }
  .banner {
    margin: 0;
    flex: none;
    padding: var(--space-2) var(--space-3);
    font-size: var(--text-sm);
  }
  .banner.error {
    background: var(--error-bg);
    color: var(--error);
  }
  .banner.warn {
    background: var(--warn-bg);
    color: var(--warn);
  }
  .link {
    background: none;
    border: 0;
    color: inherit;
    text-decoration: underline;
    cursor: pointer;
    font: inherit;
    padding: 0;
  }
  .statusbar {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-1) var(--space-3);
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    color: var(--muted);
    font-size: var(--text-xs);
    font-variant-numeric: tabular-nums;
    min-width: 0;
  }
  .statusbar span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .spacer {
    flex: 1;
  }
  .hintline {
    color: var(--faint);
  }
  .hintline.refusal {
    color: var(--warn);
  }
  .state {
    display: grid;
    flex: 1;
    align-content: center;
    justify-items: center;
    gap: var(--space-2);
    padding: var(--space-6);
    color: var(--muted);
    text-align: center;
  }
  .state h3 {
    margin: 0;
    color: var(--fg);
    font-size: var(--text-lg);
  }
  .state p {
    max-width: 52ch;
    margin: 0;
    font-size: var(--text-sm);
  }
</style>
