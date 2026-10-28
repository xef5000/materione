<script lang="ts">
  /**
   * The canvas: the GUI drawn through the shared raster (so the preview is the
   * compiled background, gui.md section 4) at a nearest-neighbour zoom of 1x to
   * 16x, with pan, selection, resize handles, snapping guides, a slot-grid
   * overlay and a pixel grid. Pointer rules: a click selects, dragging a layer
   * moves it (snapping to pixels, slots and siblings; hold Ctrl to snap to
   * nothing), dragging a handle resizes, dragging the empty canvas marquee-
   * selects, and space or the middle button pans.
   *
   * The drag never touches the history while it runs: it draws a preview
   * document, and the pointer release commits one command.
   */
  import { cssColor, parseColor } from '../../gui/raster/color';
  import { cellsOf, wellRect, slotCell, itemRect, nearestCell } from '../../gui/chest';
  import { toImageData } from '../../gui/browser-image';
  import { isLocked, type GuiLayer } from '../../gui/model';
  import * as ops from '../../gui/ops';
  import { bareType, flattenRaster, regionOf } from '../../gui/raster/flatten';
  import type { Rect } from '../../gui/raster/canvas';
  import { hitTest, layerBounds, layersInRect } from '../../gui/scene';
  import { collectTargets, snapMove, snapResize, snapThreshold } from '../../gui/snap';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { transformability } from '../../gui/registry';
  import {
    HANDLES, HANDLE_ANCHOR, HANDLE_CURSOR, normalizeRect, resizeRect, scaleWithin, unionOf,
    type Handle,
  } from '../../gui/transform';
  import { GRID_ZOOM, pinchZoom, toGui, type Point } from '../../gui/viewport';

  let { studio }: { studio: GuiStudioState } = $props();

  let stage = $state<HTMLDivElement>();
  let art = $state<HTMLCanvasElement>();
  let hover = $state<string | null>(null);
  let hoverHandle = $state<Handle | null>(null);
  let marquee = $state<Rect | null>(null);
  let panning = $state(false);
  /** Live size readout beside the selection while a transform is in flight. */
  let readout = $state<string>('');

  const view = $derived(studio.view);
  const zoom = $derived(view.zoom);
  const size = $derived(studio.size);
  const paperStyle = $derived(
    `left:${Math.round(view.panX)}px;top:${Math.round(view.panY)}px;width:${size.width * zoom}px;height:${size.height * zoom}px`,
  );

  // ---------------------------------------------------------------- drawing

  $effect(() => {
    const doc = studio.shown;
    void studio.imageVersion;
    const target = art;
    if (!target) return;
    const raster = flattenRaster(doc.layers, {
      width: size.width,
      height: size.height,
      rows: doc.rows,
      image: (reference) => studio.imageFor(reference),
    });
    target.getContext('2d')!.putImageData(toImageData(raster), 0, 0);
  });

  // ---------------------------------------------------------------- measuring

  $effect(() => {
    const node = stage;
    if (!node) return;
    let first = true;
    const observer = new ResizeObserver(() => {
      studio.stage = { w: node.clientWidth, h: node.clientHeight };
      // the window changed: a view the author has not touched keeps fitting it
      if ((first || studio.autoFit) && node.clientWidth > 0) {
        first = false;
        studio.fit();
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  });

  // The wheel must be able to preventDefault, which Svelte's passive handlers cannot.
  let wheelAccumulated = 0;
  $effect(() => {
    const node = stage;
    if (!node) return;
    const onWheel = (event: WheelEvent): void => {
      event.preventDefault();
      const box = node.getBoundingClientRect();
      const anchor = { x: event.clientX - box.left, y: event.clientY - box.top };
      const unit = event.deltaMode === 1 ? 16 : 1;
      if (event.ctrlKey) {
        studio.setView(pinchZoom(studio.view, event.deltaY * unit, anchor));
      } else if (event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) {
        studio.pan(-(event.deltaX || event.deltaY) * unit, 0);
      } else {
        wheelAccumulated += event.deltaY * unit;
        if (Math.abs(wheelAccumulated) >= 40) {
          studio.zoomStep(wheelAccumulated < 0 ? 1 : -1, anchor);
          wheelAccumulated = 0;
        }
      }
    };
    node.addEventListener('wheel', onWheel, { passive: false });
    return () => node.removeEventListener('wheel', onWheel);
  });

  function local(event: { clientX: number; clientY: number }): Point {
    const box = stage!.getBoundingClientRect();
    return { x: event.clientX - box.left, y: event.clientY - box.top };
  }

  function guiAt(event: { clientX: number; clientY: number }): Point {
    return toGui(studio.view, local(event));
  }

  const sx = (x: number): number => view.panX + x * zoom;
  const sy = (y: number): number => view.panY + y * zoom;

  // ---------------------------------------------------------------- geometry of the selection

  /** Rectangles of the selected layers (the live preview while dragging). */
  const selectedRects = $derived(
    studio.selected.flatMap((layer) => {
      const rect = layerBounds(layer, studio.scene);
      return rect ? [{ layer, rect }] : [];
    }),
  );
  const box = $derived(unionOf(selectedRects.map((entry) => entry.rect)));
  const canResize = $derived(
    selectedRects.length > 0 &&
      !studio.readOnly &&
      selectedRects.every(({ layer }) => transformability(studio.infoOf(layer), layer).resizable && !isLocked(layer)),
  );

  function handlePoint(rect: Rect, handle: Handle): Point {
    const anchor = HANDLE_ANCHOR[handle];
    return { x: sx(rect.x + rect.w * anchor.x), y: sy(rect.y + rect.h * anchor.y) };
  }

  function handleAt(point: Point): Handle | null {
    if (!box || !canResize) return null;
    for (const handle of HANDLES) {
      const at = handlePoint(box, handle);
      if (Math.abs(point.x - at.x) <= 7 && Math.abs(point.y - at.y) <= 7) return handle;
    }
    return null;
  }

  // ---------------------------------------------------------------- pointer

  type Drag =
    | { kind: 'pan'; x: number; y: number; panX: number; panY: number }
    | { kind: 'move'; start: Point; origin: Record<string, Rect>; moved: boolean; clickedId: string; wasSelected: boolean }
    | { kind: 'resize'; handle: Handle; start: Point; origin: Record<string, Rect>; box: Rect; moved: boolean }
    | { kind: 'marquee'; start: Point; base: string[]; moved: boolean; clickId?: string };
  let drag: Drag | null = null;
  let pending: ReturnType<typeof ops.setRects> = null;

  function paintAlpha(layer: GuiLayer, x: number, y: number): boolean {
    const image = typeof layer.image === 'string' ? studio.imageFor(layer.image) : null;
    return !!image && (image.get(x, y) >>> 24) > 0;
  }

  function down(event: PointerEvent): void {
    stage!.focus({ preventScroll: true });
    const point = local(event);
    if (event.button === 1 || (event.button === 0 && (studio.tool === 'hand' || studio.panKey))) {
      drag = { kind: 'pan', x: event.clientX, y: event.clientY, panX: studio.view.panX, panY: studio.view.panY };
      panning = true;
      stage!.setPointerCapture(event.pointerId);
      event.preventDefault();
      return;
    }
    if (event.button !== 0) return;
    const gui = guiAt(event);
    stage!.setPointerCapture(event.pointerId);

    const handle = handleAt(point);
    if (handle && box) {
      drag = {
        kind: 'resize',
        handle,
        start: gui,
        box,
        origin: Object.fromEntries(selectedRects.map(({ layer, rect }) => [layer.id, rect])),
        moved: false,
      };
      return;
    }

    const hit = hitTest(studio.doc, studio.scene, gui, paintAlpha);
    if (hit) {
      if (event.shiftKey || event.ctrlKey || event.metaKey) {
        studio.toggle(hit.id);
        drag = null;
        return;
      }
      const wasSelected = studio.selection.includes(hit.id);
      // A layer that fills the whole canvas (the frame) is the backdrop: dragging on it marquee-selects
      // what is on top of it, like a Figma frame. A click selects it, and once selected it moves.
      const bounds = layerBounds(hit, studio.scene);
      if (!wasSelected && bounds && bounds.x <= 0 && bounds.y <= 0 &&
          bounds.x + bounds.w >= studio.size.width && bounds.y + bounds.h >= studio.size.height) {
        drag = { kind: 'marquee', start: gui, base: [], moved: false, clickId: hit.id };
        studio.select([]);
        return;
      }
      if (!wasSelected) studio.select([hit.id]);
      const movers = studio.doc.layers.filter((layer) => studio.selection.includes(layer.id) && !isLocked(layer));
      drag = {
        kind: 'move',
        start: gui,
        origin: Object.fromEntries(
          movers.flatMap((layer) => {
            const rect = layerBounds(layer, studio.scene);
            return rect ? [[layer.id, rect] as const] : [];
          }),
        ),
        moved: false,
        clickedId: hit.id,
        wasSelected,
      };
      return;
    }
    drag = {
      kind: 'marquee',
      start: gui,
      base: event.shiftKey ? [...studio.selection] : [],
      moved: false,
    };
    if (!event.shiftKey) studio.select([]);
  }

  function snapTargetsFor(excluded: readonly string[]) {
    return collectTargets({
      width: studio.size.width,
      height: studio.size.height,
      rows: studio.doc.rows,
      slots: true,
      siblings: studio.doc.layers.flatMap((layer) => {
        if (excluded.includes(layer.id) || layer.visible === false || bareType(layer) === 'paint') return [];
        const rect = layerBounds(layer, studio.scene);
        return rect && layer.type !== 'slots' ? [rect] : [];
      }),
    });
  }

  function move(event: PointerEvent): void {
    const point = local(event);
    const gui = toGui(studio.view, point);
    studio.cursor = { x: Math.floor(gui.x), y: Math.floor(gui.y) };
    if (!drag) {
      hoverHandle = handleAt(point);
      const hit = studio.tool === 'select' && !studio.panKey && !hoverHandle
        ? hitTest(studio.doc, studio.scene, gui, paintAlpha)
        : null;
      hover = hit?.id ?? null;
      return;
    }
    const active = drag;
    if (active.kind === 'pan') {
      studio.pan(event.clientX - active.x - (studio.view.panX - active.panX), event.clientY - active.y - (studio.view.panY - active.panY));
      return;
    }
    if (active.kind === 'marquee') {
      const rect = normalizeRect(active.start, gui);
      if (!active.moved && Math.hypot(rect.w, rect.h) * studio.view.zoom < 4) return;
      active.moved = true;
      marquee = rect;
      const inside = layersInRect(studio.doc, studio.scene, rect).map((layer) => layer.id);
      studio.select([...new Set([...active.base, ...inside])]);
      return;
    }
    const dx = Math.round(gui.x - active.start.x);
    const dy = Math.round(gui.y - active.start.y);
    if (!active.moved && Math.hypot(dx, dy) * studio.view.zoom < 3) return;
    active.moved = true;
    const free = !event.ctrlKey && !event.metaKey && studio.snapping;
    const threshold = snapThreshold(studio.view.zoom);

    if (active.kind === 'move') {
      const ids = Object.keys(active.origin);
      const union = unionOf(Object.values(active.origin));
      let ddx = dx;
      let ddy = dy;
      studio.guides = [];
      const hasSlotBound = studio.doc.layers.some((layer) => ids.includes(layer.id) && transformability(studio.infoOf(layer), layer).slotBound);
      if (union && free && !hasSlotBound) {
        const result = snapMove({ ...union, x: union.x + dx, y: union.y + dy }, snapTargetsFor(ids), threshold);
        ddx += result.dx;
        ddy += result.dy;
        studio.guides = result.guides;
      }
      const rects: Record<string, Rect> = {};
      for (const [id, rect] of Object.entries(active.origin)) rects[id] = { ...rect, x: rect.x + ddx, y: rect.y + ddy };
      studio.dropSlot = null;
      for (const layer of studio.doc.layers) {
        if (ids.includes(layer.id) && transformability(studio.infoOf(layer), layer).slotBound && rects[layer.id]) {
          const r = rects[layer.id]!;
          studio.dropSlot = nearestCell(studio.doc.rows, r.x + r.w / 2, r.y + r.h / 2).index;
        }
      }
      pending = ops.setRects(studio.doc, studio.scene, rects, ids.length > 1 ? 'Move layers' : 'Move layer');
      studio.preview = pending ? pending.apply(studio.doc) : null;
      readout = union ? `${union.x + ddx}, ${union.y + ddy}` : '';
      return;
    }

    // resize
    const keep = event.shiftKey;
    const centered = event.altKey;
    let next = resizeRect(active.box, active.handle, dx, dy, { keepAspect: keep, fromCenter: centered });
    studio.guides = [];
    if (free && !centered && !keep) {
      const snapped = snapResize(next, active.handle, snapTargetsFor(Object.keys(active.origin)), threshold);
      next = snapped.rect;
      studio.guides = snapped.guides;
    }
    const rects: Record<string, Rect> = {};
    for (const [id, rect] of Object.entries(active.origin)) rects[id] = scaleWithin(rect, active.box, next);
    pending = ops.setRects(studio.doc, studio.scene, rects, 'Resize');
    studio.preview = pending ? pending.apply(studio.doc) : null;
    readout = `${next.w} x ${next.h}`;
  }

  function up(event: PointerEvent): void {
    const active = drag;
    drag = null;
    if (stage?.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
    panning = false;
    marquee = null;
    readout = '';
    studio.guides = [];
    studio.dropSlot = null;
    if (!active) return;
    if (active.kind === 'marquee' && !active.moved && active.clickId) studio.select([active.clickId]);
    if (active.kind === 'move' && !active.moved && active.wasSelected && studio.selection.length > 1) {
      studio.select([active.clickedId]);
    }
    const command = pending;
    pending = null;
    studio.preview = null;
    if ((active.kind === 'move' || active.kind === 'resize') && active.moved) studio.run(command);
  }

  function leave(): void {
    if (!drag) {
      studio.cursor = null;
      hover = null;
    }
  }

  const cursorStyle = $derived(
    panning ? 'grabbing'
      : studio.tool === 'hand' || studio.panKey ? 'grab'
        : hoverHandle ? HANDLE_CURSOR[hoverHandle]
          : hover ? 'move' : 'default',
  );

  // ---------------------------------------------------------------- overlay helpers

  const cells = $derived(cellsOf(studio.doc.rows, 'all'));

  /** A slot layer is outlined well by well (its bounding box would be the whole screen). */
  function outlines(layer: GuiLayer, rect: Rect): Rect[] {
    return bareType(layer) === 'slots' ? cellsOf(studio.doc.rows, regionOf(layer)).map(wellRect) : [rect];
  }
  const showGrid = $derived(zoom >= GRID_ZOOM && studio.showPixelGrid);
  const placeholders = $derived(studio.marked);
  /** Image layers whose texture is not available get a marked placeholder instead of silence. */
  const missingImages = $derived(
    studio.shown.layers.flatMap((layer) => {
      if (layer.visible === false || bareType(layer) !== 'image' || typeof layer.image !== 'string') return [];
      if (studio.hasImage(layer.image)) return [];
      const rect = layerBounds(layer, studio.scene);
      return rect ? [{ layer, rect }] : [];
    }),
  );

  function textFill(color: unknown): string {
    const parsed = typeof color === 'string' ? parseColor(color) : null;
    return cssColor(parsed ?? 0xff404040);
  }

  const gridStyle = $derived(
    `left:${Math.round(view.panX)}px;top:${Math.round(view.panY)}px;width:${size.width * zoom}px;height:${size.height * zoom}px;background-size:${zoom}px ${zoom}px`,
  );
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div
  bind:this={stage}
  class="stage"
  class:panning
  role="application"
  aria-label="GUI canvas"
  tabindex="0"
  style:cursor={cursorStyle}
  onpointerdown={down}
  onpointermove={move}
  onpointerup={up}
  onpointercancel={up}
  onpointerleave={leave}
  oncontextmenu={(event) => event.preventDefault()}
>
  <div class="paper" style={paperStyle}></div>
  <canvas
    bind:this={art}
    class="art"
    width={size.width}
    height={size.height}
    style={paperStyle}
    data-testid="gui-art"
  ></canvas>
  {#if showGrid}<div class="pixels" style={gridStyle}></div>{/if}

  <svg class="overlay" aria-hidden="true">
    <rect class="edge" x={sx(0) - 0.5} y={sy(0) - 0.5} width={size.width * zoom + 1} height={size.height * zoom + 1} />

    {#if studio.showSlots}
      {#each cells as cell (cell.index)}
        {@const item = itemRect(cell)}
        <rect class="slot" x={sx(item.x) + 0.5} y={sy(item.y) + 0.5} width={item.w * zoom - 1} height={item.h * zoom - 1} />
      {/each}
    {/if}
    {#if studio.dropSlot !== null}
      {@const cell = slotCell(studio.doc.rows, studio.dropSlot)}
      {#if cell}
        {@const well = wellRect(cell)}
        <rect class="drop" x={sx(well.x)} y={sy(well.y)} width={well.w * zoom} height={well.h * zoom} />
      {/if}
    {/if}

    {#each missingImages as entry (entry.layer.id)}
      <rect class="missing" x={sx(entry.rect.x) + 0.5} y={sy(entry.rect.y) + 0.5} width={entry.rect.w * zoom - 1} height={entry.rect.h * zoom - 1} />
      <text class="tag" x={sx(entry.rect.x) + 4} y={sy(entry.rect.y) + 14}>no texture</text>
    {/each}

    {#each placeholders as entry (entry.layer.id)}
      {@const rect = entry.rect}
      <g class="placeholder" data-layer={entry.layer.id}>
        <rect x={sx(rect.x)} y={sy(rect.y)} width={rect.w * zoom} height={rect.h * zoom} />
        {#if entry.target === 'text'}
          <text
            class="mc"
            x={sx(rect.x)}
            y={sy(rect.y) + 8 * zoom}
            style:font-size="{8 * zoom}px"
            fill={textFill(entry.layer.color)}
          >{typeof entry.layer.text === 'string' ? entry.layer.text : ''}</text>
        {:else}
          {@const u = rect.w * zoom}
          <polygon class="cube top" points="{sx(rect.x) + u / 2},{sy(rect.y) + u * 0.1} {sx(rect.x) + u * 0.9},{sy(rect.y) + u * 0.3} {sx(rect.x) + u / 2},{sy(rect.y) + u * 0.5} {sx(rect.x) + u * 0.1},{sy(rect.y) + u * 0.3}" />
          <polygon class="cube left" points="{sx(rect.x) + u * 0.1},{sy(rect.y) + u * 0.3} {sx(rect.x) + u / 2},{sy(rect.y) + u * 0.5} {sx(rect.x) + u / 2},{sy(rect.y) + u * 0.92} {sx(rect.x) + u * 0.1},{sy(rect.y) + u * 0.7}" />
          <polygon class="cube right" points="{sx(rect.x) + u * 0.9},{sy(rect.y) + u * 0.3} {sx(rect.x) + u / 2},{sy(rect.y) + u * 0.5} {sx(rect.x) + u / 2},{sy(rect.y) + u * 0.92} {sx(rect.x) + u * 0.9},{sy(rect.y) + u * 0.7}" />
          {#if typeof entry.layer.amount === 'number' && entry.layer.amount > 1}
            <text class="mc count" x={sx(rect.x + rect.w) } y={sy(rect.y + rect.h)} style:font-size="{8 * zoom}px">{entry.layer.amount}</text>
          {/if}
        {/if}
      </g>
    {/each}

    {#if hover && !studio.selection.includes(hover)}
      {@const hovered = studio.layerById(hover)}
      {@const rect = hovered ? layerBounds(hovered, studio.scene) : null}
      {#if rect && hovered}
        {#each outlines(hovered, rect) as part, index (index)}
          <rect class="hover" x={sx(part.x) - 0.5} y={sy(part.y) - 0.5} width={part.w * zoom + 1} height={part.h * zoom + 1} />
        {/each}
      {/if}
    {/if}

    {#each selectedRects as entry (entry.layer.id)}
      {#each outlines(entry.layer, entry.rect) as part, index (index)}
        <rect
          class="selected"
          class:locked={isLocked(entry.layer)}
          x={sx(part.x) - 0.5}
          y={sy(part.y) - 0.5}
          width={part.w * zoom + 1}
          height={part.h * zoom + 1}
        />
      {/each}
    {/each}
    {#if box && selectedRects.length > 1}
      <rect class="group" x={sx(box.x) - 1.5} y={sy(box.y) - 1.5} width={box.w * zoom + 3} height={box.h * zoom + 3} />
    {/if}
    {#if box && canResize}
      {#each HANDLES as handle (handle)}
        {@const at = handlePoint(box, handle)}
        <rect class="handle" data-handle={handle} x={at.x - 4} y={at.y - 4} width="8" height="8" />
      {/each}
    {/if}

    {#each studio.guides as guide, index (index)}
      {#if guide.axis === 'x'}
        <line class="guide" x1={sx(guide.pos)} x2={sx(guide.pos)} y1={sy(guide.from)} y2={sy(guide.to)} />
      {:else}
        <line class="guide" y1={sy(guide.pos)} y2={sy(guide.pos)} x1={sx(guide.from)} x2={sx(guide.to)} />
      {/if}
    {/each}

    {#if marquee}
      <rect class="marquee" x={sx(marquee.x)} y={sy(marquee.y)} width={marquee.w * zoom} height={marquee.h * zoom} />
    {/if}
    {#if readout && box}
      <g class="readout">
        <rect x={sx(box.x + box.w) - 4} y={sy(box.y + box.h) + 8} width={readout.length * 7 + 14} height="20" rx="4" />
        <text x={sx(box.x + box.w) + 3} y={sy(box.y + box.h) + 22}>{readout}</text>
      </g>
    {/if}
  </svg>
</div>

<style>
  .stage {
    position: relative;
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    background: var(--gui-stage);
    outline: none;
    touch-action: none;
    user-select: none;
  }
  .stage:focus-visible {
    box-shadow: inset 0 0 0 1px var(--control-line);
  }
  .paper,
  .art,
  .pixels {
    position: absolute;
    pointer-events: none;
  }
  .paper {
    background:
      conic-gradient(var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 16px 16px;
    box-shadow: var(--shadow-3);
  }
  .art {
    image-rendering: pixelated;
  }
  .pixels {
    background-image:
      linear-gradient(to right, var(--gui-pixel-grid) 1px, transparent 1px),
      linear-gradient(to bottom, var(--gui-pixel-grid) 1px, transparent 1px);
  }
  .overlay {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    overflow: hidden;
    pointer-events: none;
  }
  .edge {
    fill: none;
    stroke: var(--border-strong);
  }
  .slot {
    fill: none;
    stroke: var(--gui-slot-grid);
    stroke-dasharray: 3 3;
  }
  .drop {
    fill: var(--gui-marquee);
    stroke: var(--accent);
    stroke-width: 2;
  }
  .missing {
    fill: var(--gui-placeholder);
    stroke: var(--accent);
    stroke-dasharray: 4 3;
  }
  .tag {
    fill: var(--accent-hover);
    font-size: var(--text-xs);
  }
  .placeholder rect {
    fill: var(--gui-placeholder);
    stroke: var(--accent);
    stroke-width: 1;
    stroke-dasharray: 4 3;
  }
  .mc {
    font-family: var(--font-minecraft);
    paint-order: stroke;
  }
  .count {
    fill: var(--fg);
    text-anchor: end;
  }
  .cube {
    stroke: var(--accent);
    stroke-width: 1;
  }
  .cube.top {
    fill: var(--accent-hover);
  }
  .cube.left {
    fill: var(--accent);
  }
  .cube.right {
    fill: var(--accent-low);
  }
  .hover {
    fill: none;
    stroke: var(--gui-hover);
    stroke-width: 1;
  }
  .selected {
    fill: none;
    stroke: var(--accent);
    stroke-width: 1;
  }
  .selected.locked {
    stroke-dasharray: 4 3;
  }
  .group {
    fill: none;
    stroke: var(--accent);
    stroke-width: 1;
    stroke-dasharray: 2 3;
  }
  .handle {
    fill: var(--canvas-mark);
    stroke: var(--accent);
    stroke-width: 1.5;
  }
  .guide {
    stroke: var(--gui-guide);
    stroke-width: 1;
  }
  .marquee {
    fill: var(--gui-marquee);
    stroke: var(--accent);
    stroke-width: 1;
  }
  .readout rect {
    fill: var(--bg-raised);
    stroke: var(--border-strong);
  }
  .readout text {
    fill: var(--fg);
    font-size: var(--text-xs);
    font-variant-numeric: tabular-nums;
  }
</style>
