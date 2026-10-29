<script lang="ts">
  /**
   * The GUI studio (gui.md section 6): a canvas tool, not a form. It owns the
   * whole main area for one `guis/**` document: toolbar, components palette and
   * layers on the left, the pixel-perfect canvas in the middle, the registry-
   * derived inspector on the right. It is a client of the document ops like any
   * other studio: it loads through the draft store, edits a local command
   * history, and saves as one authoring transaction, so the file on disk stays
   * the single source of truth (D1).
   */
  import ComponentPalette from './ComponentPalette.svelte';
  import GuiCanvas from './GuiCanvas.svelte';
  import PreviewInGame from './PreviewInGame.svelte';
  import InspectorPanel from './InspectorPanel.svelte';
  import ImageChooser from './ImageChooser.svelte';
  import LayersPanel from './LayersPanel.svelte';
  import PaintOptions from './PaintOptions.svelte';
  import Icon from '../../components/Icon.svelte';
  import type { IconName } from '../../components/icons';
  import { ops, unwrap } from '../../api';
  import type { ResourceRef } from '../../api';
  import {
    draftDirty,
    draftStore,
    ensureDocumentDraft,
    getDocumentDraft,
    reloadDocumentDraft,
    retainDocumentDraft,
    saveDocumentDraft,
  } from '../../drafts.svelte';
  import { isCustomized } from '../../gui/customize';
  import { itemRefOf } from '../../gui/items';
  import { bareType } from '../../gui/raster/flatten';
  import { readRegistry } from '../../gui/registry';
  import { GuiStudioState, isPaintTool } from '../../gui/studio.svelte';
  import { isTextLayer } from '../../gui/text';
  import { zoomLabel } from '../../gui/viewport';
  import { restack } from '../../gui/ops';
  import { rootIdForResource } from '../../studio';
  import { registerEditorGuard, revealInAdvanced, session, workspace } from '../../state.svelte';

  interface Props {
    resource: ResourceRef;
    onClose: () => void;
    active?: boolean;
  }

  let { resource, onClose, active = true }: Props = $props();

  // One studio state per opened document: ContentView keys this component by resource.
  // svelte-ignore state_referenced_locally
  const studio = new GuiStudioState(resource.pack, resource.path, rootIdForResource(resource.id));
  const documentDraft = $derived(getDocumentDraft(resource.pack, resource.path));

  const rootId = $derived(rootIdForResource(resource.id));
  let notGui = $state(false);

  $effect(() => retainDocumentDraft(documentDraft));

  $effect(() => {
    const current = documentDraft;
    const channel = session.channel;
    if (channel && !current.loaded && !current.loading && !current.error) {
      void ensureDocumentDraft(current, channel);
    }
  });

  $effect(() => {
    if (documentDraft.loaded && !studio.loaded) {
      notGui = !studio.load(documentDraft.base, rootId, documentDraft.rev);
    }
  });

  // The file changed on the server (another editor, a hand edit): an untouched studio follows it,
  // a dirty one keeps the author's work and says so through the draft's own conflict error.
  $effect(() => {
    const rev = documentDraft.rev;
    if (studio.loaded && documentDraft.loaded && rev !== studio.seenRev && !studio.dirty && !studio.saving) {
      notGui = !studio.reload(documentDraft.base, rev);
    }
  });

  // The registry describes the components: palette, inspector and defaults all come from it.
  $effect(() => {
    const channel = session.channel;
    if (!channel) return;
    void (async () => {
      try {
        const registry = readRegistry(unwrap(await ops.describe(channel)));
        studio.setRegistry(registry, registry ? '' : 'This server does not describe GUI components yet.');
      } catch (error) {
        studio.setRegistry(null, error instanceof Error ? error.message : String(error));
      }
    })();
  });

  // Textures the layers name are read through the asset store, a few at a time.
  $effect(() => {
    void studio.doc;
    void studio.loaded;
    if (studio.loaded) void studio.ensureImages(session.channel);
  });

  // The compiler's verdict follows the document: asked again once editing pauses. A stroke changes the
  // pixels without changing the document, so the pixel counters are read too.
  $effect(() => {
    void studio.doc;
    void studio.preview;
    void studio.imageVersion;
    void studio.dirty;
    if (studio.loaded) studio.scheduleCheck(session.channel);
  });

  $effect(() => registerEditorGuard('studio', () => {
    if (studio.saving) return false;
    return !anyDirty || confirm('Leave the GUI studio without saving? Your layout changes will be lost.');
  }, () => anyDirty || studio.saving));

  const textures = $derived(
    workspace.assets
      .filter((asset) => asset.path.startsWith('assets/textures/') && asset.kind === 'png')
      .map((asset) => asset.path.slice('assets/textures/'.length).replace(/\.png$/, '')),
  );

  /** The ordinary items this GUI's layers were customized into: their appearance edits are drafts of their own. */
  const customItems = $derived(
    studio.doc.layers.flatMap((layer) => {
      if (bareType(layer) !== 'item') return [];
      const item = isCustomized(itemRefOf(layer), { pack: resource.pack, guiPath: resource.path, resources: workspace.resources });
      // only drafts that exist: the inline appearance opens (and so creates) one when the layer is selected
      const draft = item ? draftStore.documents.find((entry) => entry.pack === item.pack && entry.path === item.path) : undefined;
      return draft ? [draft] : [];
    }),
  );
  const itemsDirty = $derived(customItems.some((draft) => draftDirty(draft)));
  const anyDirty = $derived(studio.dirty || itemsDirty);

  /** One Save: the layout (and its pixel assets) in one transaction, then each customized item's own draft. */
  async function save(): Promise<void> {
    const channel = session.channel;
    if (!channel || !studio.loaded) return;
    await studio.save(channel, documentDraft);
    for (const draft of customItems) {
      if (draftDirty(draft)) await saveDocumentDraft(draft, channel);
    }
  }

  async function reloadFromServer(): Promise<void> {
    const channel = session.channel;
    if (!channel) return;
    await reloadDocumentDraft(documentDraft, channel, true);
    studio.reload(documentDraft.base, documentDraft.rev);
  }

  // ---------------------------------------------------------------- keyboard

  function typing(target: EventTarget | null): boolean {
    const node = target as HTMLElement | null;
    return !!node && (node.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(node.tagName));
  }

  function keydown(event: KeyboardEvent): void {
    if (!active) return;
    const mod = event.ctrlKey || event.metaKey;
    const key = event.key.toLowerCase();
    if (mod && key === 's') {
      event.preventDefault();
      void save();
      return;
    }
    if (typing(event.target)) return;
    if (event.key === ' ') {
      // Space on a focused control still activates it; only elsewhere does it mean "pan".
      if ((event.target as HTMLElement | null)?.closest('button, a, [role="button"]')) return;
      event.preventDefault();
      studio.panKey = true;
      return;
    }
    if (mod && key === 'z') {
      event.preventDefault();
      if (event.shiftKey) studio.redo();
      else studio.undo();
    } else if (mod && key === 'y') {
      event.preventDefault();
      studio.redo();
    } else if (mod && key === 'd') {
      event.preventDefault();
      studio.duplicateSelection();
    } else if (mod && key === 'a') {
      event.preventDefault();
      studio.selectAll();
    } else if (mod && (event.key === ']' || event.key === '[')) {
      event.preventDefault();
      studio.run(restack(studio.doc, studio.selection, event.key === ']' ? 'forward' : 'backward'));
    } else if (!mod && key === 'v') {
      studio.chooseTool('select');
    } else if (!mod && key === 'h') {
      studio.chooseTool('hand');
    } else if (!mod && key === 'b') {
      studio.chooseTool('brush');
    } else if (!mod && key === 'e') {
      studio.chooseTool('eraser');
    } else if (!mod && key === 'g') {
      studio.chooseTool('fill');
    } else if (!mod && key === 'i') {
      studio.chooseTool('picker');
    } else if (!mod && (event.key === '[' || event.key === ']')) {
      studio.setBrush({ size: studio.brush.size + (event.key === ']' ? 1 : -1) });
    } else if (!mod && event.key === 'Enter' && studio.selected.length === 1 && isTextLayer(studio.selected[0]!)) {
      event.preventDefault();
      studio.beginTextEdit(studio.selection[0]!);
    } else if (!mod && event.key === '0') {
      studio.fit();
    } else if (!mod && event.key === '1') {
      studio.actualSize();
    } else if (!mod && (event.key === '=' || event.key === '+')) {
      studio.zoomStep(1);
    } else if (!mod && event.key === '-') {
      studio.zoomStep(-1);
    } else if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      studio.deleteSelection();
    } else if (event.key === 'F2' && studio.selection.length === 1) {
      studio.renaming = studio.selection[0]!;
    } else if (event.key === 'Escape') {
      if (isPaintTool(studio.tool)) studio.chooseTool('select');
      else studio.select([]);
    } else if (event.key.startsWith('Arrow') && studio.selection.length) {
      event.preventDefault();
      const step = event.shiftKey ? 10 : 1;
      studio.nudgeSelection(
        event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0,
        event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0,
      );
    }
  }

  function keyup(event: KeyboardEvent): void {
    if (event.key === ' ') studio.panKey = false;
  }

  const status = $derived(
    studio.readOnly ? 'Read-only' : studio.saving ? 'Saving…' : anyDirty ? 'Unsaved changes' : 'Saved',
  );
  const selectionText = $derived.by(() => {
    if (studio.selected.length === 0) return `${studio.size.width} x ${studio.size.height} px, ${studio.doc.rows} row${studio.doc.rows === 1 ? '' : 's'}`;
    if (studio.selected.length > 1) return `${studio.selected.length} layers`;
    const rect = studio.boundsOf(studio.selected[0]!);
    return rect ? `${studio.selected[0]!.id} · ${rect.w} x ${rect.h}` : studio.selected[0]!.id;
  });
</script>

<svelte:window onkeydown={keydown} onkeyup={keyup} />

{#snippet tool(icon: IconName, label: string, shortcut: string, onclick: () => void, pressed?: boolean, disabled = false)}
  <button
    type="button"
    class="tool"
    class:on={pressed}
    title="{label} ({shortcut})"
    aria-label={label}
    aria-pressed={pressed}
    {disabled}
    {onclick}
  ><Icon name={icon} size={16} /></button>
{/snippet}

<section class="gui-studio view-enter" aria-label="GUI studio">
  <header class="bar">
    <button type="button" class="back" onclick={onClose} title="back to GUIs" aria-label="back to GUIs">
      <Icon name="arrow-left" size={16} />
    </button>
    <h2 class="id" title="revision {documentDraft.rev}">{resource.id}</h2>
    <span class="status" class:dirty={anyDirty} class:readonly={studio.readOnly} data-status>{status}</span>
    <span class="spacer"></span>
    <button type="button" class="ghost" onclick={() => revealInAdvanced(resource)} title="open this resource in Files">Open in Files</button>
    <PreviewInGame {resource} dirty={anyDirty} />
    <button type="button" class="save" disabled={!active || !studio.loaded || studio.readOnly || !anyDirty || studio.saving} onclick={save} title="Save (Ctrl+S)">
      <Icon name="save" size={14} /> {studio.saving ? 'Saving…' : 'Save'}
    </button>
  </header>

  <div class="tools" role="toolbar" aria-label="Studio tools">
    <div class="cluster">
      {@render tool('mouse-pointer-2', 'Select', 'V', () => studio.chooseTool('select'), studio.tool === 'select')}
      {@render tool('hand', 'Hand', 'H', () => studio.chooseTool('hand'), studio.tool === 'hand')}
    </div>
    <span class="rule"></span>
    <div class="cluster">
      {@render tool('pencil', 'Brush', 'B', () => studio.chooseTool('brush'), studio.tool === 'brush', studio.readOnly)}
      {@render tool('eraser', 'Eraser', 'E', () => studio.chooseTool('eraser'), studio.tool === 'eraser', studio.readOnly)}
      {@render tool('paint-bucket', 'Fill', 'G', () => studio.chooseTool('fill'), studio.tool === 'fill', studio.readOnly)}
      {@render tool('pipette', 'Eyedropper', 'I', () => studio.chooseTool('picker'), studio.tool === 'picker', studio.readOnly)}
    </div>
    <span class="rule"></span>
    <div class="cluster">
      {@render tool('zoom-out', 'Zoom out', '-', () => studio.zoomStep(-1))}
      <button type="button" class="zoom" title="Actual size (1)" onclick={() => studio.actualSize()} data-zoom>{zoomLabel(studio.view.zoom)}</button>
      {@render tool('zoom-in', 'Zoom in', '+', () => studio.zoomStep(1))}
      {@render tool('scan', 'Fit to window', '0', () => studio.fit())}
    </div>
    <span class="rule"></span>
    <div class="cluster">
      {@render tool('layout-grid', 'Slot grid', 'overlay', () => (studio.showSlots = !studio.showSlots), studio.showSlots)}
      {@render tool('grid-3x3', 'Pixel grid (from 8x)', 'overlay', () => (studio.showPixelGrid = !studio.showPixelGrid), studio.showPixelGrid)}
      {@render tool('magnet', 'Snapping (hold Ctrl to skip)', 'toggle', () => (studio.snapping = !studio.snapping), studio.snapping)}
    </div>
    <span class="rule"></span>
    <div class="cluster">
      {@render tool('undo-2', studio.undoLabel ? `Undo ${studio.undoLabel}` : 'Undo', 'Ctrl+Z', () => studio.undo(), undefined, !studio.canUndo || studio.readOnly)}
      {@render tool('redo-2', studio.redoLabel ? `Redo ${studio.redoLabel}` : 'Redo', 'Ctrl+Shift+Z', () => studio.redo(), undefined, !studio.canRedo || studio.readOnly)}
    </div>
  </div>

  {#if isPaintTool(studio.tool)}<PaintOptions {studio} />{/if}

  {#if documentDraft.error}<p class="banner error" role="alert">{documentDraft.error}</p>{/if}
  {#if studio.saveError}
    <p class="banner error" role="alert">
      {studio.saveError}
      {#if studio.conflict}<button type="button" class="link" onclick={reloadFromServer}>Reload and discard my edits</button>{/if}
    </p>
  {/if}
  {#if studio.readOnly}
    <p class="banner warn" role="status">
      This file has {studio.blocking.join('; ')}. The studio shows it but will not rewrite what it cannot hold faithfully;
      <button type="button" class="link" onclick={() => revealInAdvanced(resource)}>edit it in Files</button>.
    </p>
  {/if}
  {#if notGui}
    <p class="banner error" role="alert">This file is not a GUI document (no layers).</p>
  {/if}
  {#if studio.checks === 'unavailable'}
    <p class="banner notice" role="status" data-testid="gui-api-unavailable">
      The compiler's checks (skipped layers, flags) and Customize need a Materione plugin with the GUI API.
      This connection does not advertise it, so layers are not checked here.
    </p>
  {:else if studio.checks === 'failed'}
    <p class="banner warn" role="status">The compiler could not check this document: {studio.checkError}</p>
  {/if}
  {#each studio.documentFlags as message (message)}
    <p class="banner warn" role="status" data-testid="gui-verdict-document">The compiler says of this GUI: {message}</p>
  {/each}
  {#if studio.notice && !anyDirty}<p class="banner notice" role="status">{studio.notice}</p>{/if}

  <div class="body">
    <aside class="left">
      <ComponentPalette {studio} />
      <LayersPanel {studio} />
    </aside>
    <div class="center">
      {#if documentDraft.loading && !studio.loaded}
        <p class="loading">Opening…</p>
      {:else}
        <GuiCanvas {studio} {textures} />
      {/if}
      <footer class="statusbar">
        <span class="cursor" data-cursor>{studio.cursor ? `${studio.cursor.x}, ${studio.cursor.y}` : '–'}</span>
        <span class="sel">{selectionText}</span>
        <span class="spacer"></span>
        <span class="hintline" class:say={!!studio.hint} role="status" data-testid="gui-hint">{studio.hint || (isPaintTool(studio.tool) ? 'Space-drag pans · wheel zooms · [ ] change the brush size' : 'Space or middle-drag to pan · wheel to zoom · Shift+click to multi-select · double-click text to edit')}</span>
      </footer>
    </div>
    <div class="right"><InspectorPanel {studio} {textures} /></div>
  </div>
  {#if studio.choosing}<ImageChooser {studio} {textures} />{/if}
</section>

<style>
  .gui-studio {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    overflow: hidden;
    position: relative;
  }
  .bar {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .back {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    padding: 0 var(--space-1);
  }
  .back:hover {
    color: var(--fg);
  }
  .id {
    margin: 0;
    font: 600 var(--text-md) var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .status {
    flex: none;
    padding: 0 var(--space-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    color: var(--muted);
    font-size: var(--text-xs);
    line-height: 1.7;
  }
  .status.dirty {
    border-color: var(--accent);
    color: var(--accent-hover);
  }
  .status.readonly {
    border-color: var(--warn);
    color: var(--warn);
  }
  .spacer {
    flex: 1;
    min-width: 0;
  }
  .ghost,
  .save {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  .ghost:hover {
    background: var(--bg-hover);
  }
  .save {
    background: var(--accent);
    border-color: transparent;
    color: var(--accent-ink);
    font-weight: 600;
  }
  .save:disabled {
    background: none;
    border-color: var(--border);
    color: var(--faint);
    cursor: default;
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
  .cluster {
    display: flex;
    align-items: center;
    gap: var(--space-half);
  }
  .rule {
    align-self: stretch;
    width: 1px;
    margin: var(--space-1) var(--space-1);
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
  .tool.on {
    background: var(--accent-low);
    border-color: var(--accent);
    color: var(--accent-hover);
  }
  .tool:disabled {
    opacity: 0.35;
    cursor: default;
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
  .banner.notice {
    background: var(--ok-bg);
    color: var(--ok);
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
  .body {
    display: grid;
    flex: 1 1 0;
    min-height: 0;
    min-width: 0;
    grid-template-columns: minmax(180px, 240px) minmax(0, 1fr) minmax(200px, 272px);
  }
  .left {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    border-right: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .center {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
  }
  .right {
    min-width: 0;
    min-height: 0;
    border-left: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .loading {
    margin: var(--space-6);
    color: var(--muted);
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
  .cursor {
    min-width: 64px;
  }
  .hintline {
    color: var(--faint);
  }
  .hintline.say {
    color: var(--accent-hover);
  }
</style>
