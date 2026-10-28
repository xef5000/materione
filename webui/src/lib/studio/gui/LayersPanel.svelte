<script lang="ts">
  /**
   * The layers panel: the document's `layers:` list, top of the stack first.
   * Drag a row to reorder (the whole selection moves together), double-click a
   * name to rename it in place, and use the eye and lock to hide or protect a
   * layer. A badge marks a layer whose list position cannot be honored because
   * its render target is fixed (raster under text under slot items).
   */
  import { tick } from 'svelte';
  import Icon from '../../components/Icon.svelte';
  import { moveLayers } from '../../gui/commands';
  import { layerLabel, isHidden, isLocked, type GuiLayer } from '../../gui/model';
  import * as ops from '../../gui/ops';
  import { componentIcon } from '../../gui/registry';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio }: { studio: GuiStudioState } = $props();

  /** Top of the stack first, like every layers panel. */
  const rows = $derived([...studio.doc.layers].reverse());
  let dragging = $state<string[]>([]);
  let dropAt = $state<{ id: string; below: boolean } | null>(null);
  let renameInput = $state<HTMLInputElement>();
  let anchor: string | null = null;

  $effect(() => {
    if (studio.renaming) {
      void tick().then(() => {
        renameInput?.focus();
        renameInput?.select();
      });
    }
  });

  function click(event: MouseEvent, layer: GuiLayer): void {
    if (event.shiftKey && anchor) {
      const ids = studio.doc.layers.map((entry) => entry.id);
      const [from, to] = [ids.indexOf(anchor), ids.indexOf(layer.id)].sort((a, b) => a - b);
      studio.select(ids.slice(from, to! + 1));
    } else if (event.ctrlKey || event.metaKey) {
      studio.toggle(layer.id);
      anchor = layer.id;
    } else {
      studio.select([layer.id]);
      anchor = layer.id;
    }
  }

  function dragStart(event: DragEvent, layer: GuiLayer): void {
    const ids = studio.selection.includes(layer.id) ? studio.selection : [layer.id];
    if (!studio.selection.includes(layer.id)) studio.select([layer.id]);
    dragging = ids;
    event.dataTransfer?.setData('text/plain', ids.join(','));
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  function dragOver(event: DragEvent, layer: GuiLayer): void {
    if (!dragging.length) return;
    event.preventDefault();
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    dropAt = { id: layer.id, below: event.clientY > box.top + box.height / 2 };
  }

  function drop(event: DragEvent): void {
    event.preventDefault();
    const target = dropAt;
    const ids = dragging;
    dragging = [];
    dropAt = null;
    if (!target || !ids.length || ids.includes(target.id)) return;
    const rest = studio.doc.layers.filter((layer) => !ids.includes(layer.id));
    const at = rest.findIndex((layer) => layer.id === target.id);
    // Rows are top-first: dropping below a row means just underneath it, i.e. at its list index.
    studio.run(moveLayers(studio.doc, ids, target.below ? at : at + 1));
  }

  function endDrag(): void {
    dragging = [];
    dropAt = null;
  }

  function commitRename(layer: GuiLayer, value: string): void {
    studio.renaming = null;
    studio.run(ops.rename(studio.doc, layer.id, value));
  }
</script>

<section class="layers" aria-label="Layers">
  <header>
    <h3>Layers <span>{studio.doc.layers.length}</span></h3>
    <div class="actions">
      <button type="button" title="Raise (Ctrl+])" aria-label="Raise layer" disabled={!studio.selection.length || studio.readOnly}
        onclick={() => studio.run(ops.restack(studio.doc, studio.selection, 'forward'))}><Icon name="chevron-up" size={14} /></button>
      <button type="button" title="Lower (Ctrl+[)" aria-label="Lower layer" disabled={!studio.selection.length || studio.readOnly}
        onclick={() => studio.run(ops.restack(studio.doc, studio.selection, 'backward'))}><Icon name="chevron-down" size={14} /></button>
      <button type="button" title="Duplicate (Ctrl+D)" aria-label="Duplicate layer" disabled={!studio.selection.length || studio.readOnly}
        onclick={() => studio.duplicateSelection()}><Icon name="copy" size={14} /></button>
      <button type="button" title="Delete (Del)" aria-label="Delete layer" disabled={!studio.selection.length || studio.readOnly}
        onclick={() => studio.deleteSelection()}><Icon name="trash-2" size={14} /></button>
    </div>
  </header>
  <ul>
    {#each rows as layer (layer.id)}
      {@const badge = studio.badges.get(layer.id)}
      {@const info = studio.infoOf(layer)}
      <li
        class="row"
        class:selected={studio.selection.includes(layer.id)}
        class:hidden={isHidden(layer)}
        class:locked={isLocked(layer)}
        class:dragging={dragging.includes(layer.id)}
        class:before={dropAt?.id === layer.id && !dropAt.below}
        class:after={dropAt?.id === layer.id && dropAt.below}
        draggable={!studio.readOnly && studio.renaming !== layer.id}
        aria-current={studio.selection.includes(layer.id) ? 'true' : undefined}
        ondragstart={(event) => dragStart(event, layer)}
        ondragover={(event) => dragOver(event, layer)}
        ondrop={drop}
        ondragend={endDrag}
      >
        <span class="grip" aria-hidden="true"><Icon name="grip-vertical" size={14} /></span>
        {#if studio.renaming === layer.id}
          <div class="pick">
            <span class="type"><Icon name={componentIcon(info)} size={14} /></span>
            <input
              class="rename"
              bind:this={renameInput}
              aria-label="Layer name"
              value={layerLabel(layer)}
              onblur={(event) => studio.renaming === layer.id && commitRename(layer, event.currentTarget.value)}
              onkeydown={(event) => {
                event.stopPropagation();
                if (event.key === 'Enter') commitRename(layer, event.currentTarget.value);
                if (event.key === 'Escape') studio.renaming = null;
              }}
            />
          </div>
        {:else}
          <button class="pick" type="button" onclick={(event) => click(event, layer)} ondblclick={() => !studio.readOnly && (studio.renaming = layer.id)}>
            <span class="type"><Icon name={componentIcon(info)} size={14} /></span>
            <span class="name">{layerLabel(layer)}</span>
          </button>
        {/if}
        {#if badge}
          <span class="badge" title={badge.label} role="img" aria-label={badge.label}>#{badge.position}</span>
        {/if}
        <button
          class="toggle"
          type="button"
          disabled={studio.readOnly}
          title={isHidden(layer) ? 'Show' : 'Hide'}
          aria-label="{isHidden(layer) ? 'Show' : 'Hide'} {layerLabel(layer)}"
          aria-pressed={isHidden(layer)}
          onclick={() => studio.run(ops.setVisible(studio.doc, layer.id, isHidden(layer)))}
        ><Icon name={isHidden(layer) ? 'eye-off' : 'eye'} size={14} /></button>
        <button
          class="toggle lock"
          class:on={isLocked(layer)}
          type="button"
          disabled={studio.readOnly}
          title={isLocked(layer) ? 'Unlock' : 'Lock'}
          aria-label="{isLocked(layer) ? 'Unlock' : 'Lock'} {layerLabel(layer)}"
          aria-pressed={isLocked(layer)}
          onclick={() => studio.run(ops.setLocked(studio.doc, layer.id, !isLocked(layer)))}
        ><Icon name={isLocked(layer) ? 'lock' : 'lock-open'} size={14} /></button>
      </li>
    {/each}
  </ul>
</section>

<style>
  .layers {
    display: flex;
    flex-direction: column;
    flex: 1 1 0;
    min-height: 0;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-3) var(--space-3) var(--space-2);
  }
  h3 {
    margin: 0;
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  h3 span {
    margin-left: var(--space-1);
    color: var(--faint);
    font-weight: 500;
  }
  .actions {
    display: flex;
    gap: var(--space-half);
  }
  .actions button,
  .toggle {
    display: inline-grid;
    place-items: center;
    width: 24px;
    height: 24px;
    background: transparent;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .actions button:hover:not(:disabled),
  .toggle:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .actions button:disabled {
    opacity: 0.35;
    cursor: default;
  }
  ul {
    flex: 1 1 0;
    min-height: 0;
    margin: 0;
    padding: 0 var(--space-2) var(--space-3);
    list-style: none;
    overflow-y: auto;
    overflow-x: hidden;
  }
  .row {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--space-half);
    min-width: 0;
    padding-right: var(--space-1);
    border-radius: var(--radius-sm);
    border: 1px solid transparent;
  }
  .row:hover {
    background: var(--bg-raised);
  }
  .row.selected {
    background: var(--accent-low);
    border-color: var(--accent);
  }
  .row.dragging {
    opacity: 0.45;
  }
  .row.before::before,
  .row.after::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    height: 2px;
    background: var(--accent);
    border-radius: var(--radius-sm);
  }
  .row.before::before {
    top: -2px;
  }
  .row.after::after {
    bottom: -2px;
  }
  .grip {
    flex: none;
    display: inline-grid;
    place-items: center;
    width: 16px;
    color: var(--faint);
    cursor: grab;
  }
  .pick {
    flex: 1 1 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    padding: var(--space-1) var(--space-1);
    background: transparent;
    border: 0;
    color: var(--fg);
    font-size: var(--text-sm);
    text-align: left;
    cursor: pointer;
  }
  .type {
    flex: none;
    display: inline-grid;
    color: var(--muted);
  }
  .name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .hidden .name,
  .hidden .type {
    opacity: 0.45;
    text-decoration: line-through;
  }
  .rename {
    flex: 1 1 0;
    min-width: 0;
    width: 100%;
    background: var(--bg-input);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
    padding: var(--space-half) var(--space-1);
    font-size: var(--text-sm);
    outline: none;
  }
  .badge {
    flex: none;
    padding: 0 var(--space-1);
    background: var(--warn-bg);
    border: 1px solid var(--warn);
    border-radius: var(--radius-pill);
    color: var(--warn);
    font-size: var(--text-xs);
    font-variant-numeric: tabular-nums;
    line-height: 1.5;
  }
  .lock.on {
    color: var(--accent);
  }
</style>
