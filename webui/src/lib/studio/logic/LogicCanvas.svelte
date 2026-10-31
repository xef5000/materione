<script lang="ts">
  /**
   * The node canvas (logic.md §7): the logic of the document drawn as nodes joined by stepped "redstone" wires,
   * with pan, zoom, selection and the gestures that edit it.
   *
   *   - drag a node by its header to move it (its position is the only thing written);
   *   - drag from an output port to the empty canvas to add a node there, press Enter on a port for the same;
   *   - drag a node's input dot onto a free output port to lift the node and the steps behind it and move them;
   *   - drag from an output port onto a node to try to connect them: a join, a loop or a second wire out of a port
   *     is refused with the reason, because the file could not say it (§7.2);
   *   - space or middle-drag, or drag the background, to pan; the wheel zooms; F frames; / opens the palette.
   *
   * A gesture never touches the document until it ends: a drag draws an override, and the release makes one
   * history command, so one undo takes it back (shared with the Design tab).
   */
  import Icon from '../../components/Icon.svelte';
  import type { LogicCanvasState } from '../../logic/canvas.svelte';
  import { GRID, inputAnchor, outputAnchor, pathOf, route, type Point } from '../../logic/layout';
  import { planConnect, planMoveChain, slotOf } from '../../logic/plan';
  import { portKey } from '../../logic/project';
  import { toWorld } from '../../logic/viewport';
  import { session } from '../../state.svelte';
  import LogicNode from './LogicNode.svelte';
  import LogicPalette from './LogicPalette.svelte';

  let { canvas, active = true }: { canvas: LogicCanvasState; active?: boolean } = $props();

  type Gesture =
    | { kind: 'pan'; start: Point; panX: number; panY: number }
    | { kind: 'node'; id: string; start: Point; origin: Point; moved: boolean; at: Point }
    | { kind: 'wire'; node: string; port: string; start: Point; moved: boolean; pointer: Point }
    | { kind: 'pickup'; id: string; start: Point; moved: boolean; pointer: Point };

  let stage = $state<HTMLDivElement>();
  let gesture = $state.raw<Gesture | null>(null);
  let spaceHeld = $state(false);
  let panning = $state(false);
  /** The node a keyboard move landed on, so focus follows the selection. */
  let followFocus = false;

  const THRESHOLD = 4;
  const snap = (value: number): number => Math.round(value / GRID) * GRID;

  // ---------------------------------------------------------------- measuring

  $effect(() => {
    const node = stage;
    if (!node) return;
    let first = true;
    const observer = new ResizeObserver(() => {
      canvas.stage = { w: node.clientWidth, h: node.clientHeight };
      if ((first || canvas.autoFit) && node.clientWidth > 0) {
        first = false;
        canvas.glide = false;
        canvas.fit();
        canvas.glide = false;
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  });

  // The wheel must be able to preventDefault, which Svelte's passive handlers cannot.
  $effect(() => {
    const node = stage;
    if (!node) return;
    const onWheel = (event: WheelEvent): void => {
      if ((event.target as HTMLElement).closest('.palette')) return;
      event.preventDefault();
      const box = node.getBoundingClientRect();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 120 : 1;
      canvas.wheel(event.deltaY * unit, { x: event.clientX - box.left, y: event.clientY - box.top });
    };
    node.addEventListener('wheel', onWheel, { passive: false });
    return () => node.removeEventListener('wheel', onWheel);
  });

  // A selection moved by the keyboard takes focus with it.
  $effect(() => {
    const id = canvas.selection;
    if (!id || !followFocus || !stage) return;
    followFocus = false;
    stage.querySelector<HTMLElement>(`[data-node="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
  });

  // The compiler's verdict follows the draft: asked again once editing pauses.
  $effect(() => {
    void canvas.block;
    void canvas.status;
    canvas.scheduleCheck(session.channel);
  });

  // ---------------------------------------------------------------- pointer

  function local(event: PointerEvent): Point {
    const box = stage!.getBoundingClientRect();
    return { x: event.clientX - box.left, y: event.clientY - box.top };
  }

  function worldOf(point: Point): Point {
    return toWorld(canvas.view, point);
  }

  function down(event: PointerEvent): void {
    if (!stage || gesture) return;
    const target = event.target as HTMLElement;
    if (target.closest('.palette, .overlay, .mini, input')) return;
    const point = local(event);
    if (event.button === 1 || (event.button === 0 && spaceHeld)) {
      event.preventDefault();
      begin(event, { kind: 'pan', start: point, panX: canvas.view.panX, panY: canvas.view.panY });
      panning = true;
      return;
    }
    if (event.button !== 0) return;
    const out = target.closest<HTMLElement>('[data-port-out]');
    if (out) {
      canvas.select(out.dataset.node ?? null);
      begin(event, { kind: 'wire', node: out.dataset.node!, port: out.dataset.port!, start: point, moved: false, pointer: worldOf(point) });
      return;
    }
    const input = target.closest<HTMLElement>('[data-port-in]');
    if (input) {
      const id = input.dataset.node!;
      canvas.select(id);
      if (!canvas.graph.feeder.has(id)) return;
      begin(event, { kind: 'pickup', id, start: point, moved: false, pointer: worldOf(point) });
      return;
    }
    const nodeElement = target.closest<HTMLElement>('[data-node]');
    if (nodeElement) {
      const id = nodeElement.dataset.node!;
      canvas.select(id);
      const rect = canvas.placed.rects.get(id);
      if (rect) begin(event, { kind: 'node', id, start: point, origin: { x: rect.x, y: rect.y }, moved: false, at: { x: rect.x, y: rect.y } });
      nodeElement.focus({ preventScroll: true });
      return;
    }
    canvas.select(null);
    stage.focus({ preventScroll: true });
    begin(event, { kind: 'pan', start: point, panX: canvas.view.panX, panY: canvas.view.panY });
    panning = true;
  }

  function begin(event: PointerEvent, next: Gesture): void {
    gesture = next;
    stage!.setPointerCapture(event.pointerId);
  }

  function move(event: PointerEvent): void {
    const current = gesture;
    if (!current) return;
    const point = local(event);
    const far = Math.hypot(point.x - current.start.x, point.y - current.start.y) >= THRESHOLD;
    if (current.kind === 'pan') {
      canvas.glide = false;
      canvas.setView({ zoom: canvas.view.zoom, panX: current.panX + point.x - current.start.x, panY: current.panY + point.y - current.start.y });
    } else if (current.kind === 'node') {
      const moved = current.moved || far;
      if (!moved) return;
      const zoom = canvas.view.zoom;
      const at = { x: snap(current.origin.x + (point.x - current.start.x) / zoom), y: snap(current.origin.y + (point.y - current.start.y) / zoom) };
      gesture = { ...current, moved, at };
      canvas.dragging = new Map([[current.id, at]]);
    } else {
      const moved = current.moved || far;
      gesture = { ...current, moved, pointer: worldOf(point) };
      if (moved) hover(event);
    }
  }

  /** What dropping the wire here would do, said in the status line while the pointer is still down. */
  function hover(event: PointerEvent): void {
    const current = gesture;
    if (!current || (current.kind !== 'wire' && current.kind !== 'pickup')) return;
    const under = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null;
    const graph = canvas.graph;
    if (current.kind === 'wire') {
      const node = under?.closest<HTMLElement>('[data-node]')?.dataset.node;
      if (node) {
        const plan = planConnect(graph, canvas.block, current, node, canvas.nameOf);
        canvas.say(plan.ok ? 'Release to connect.' : plan.reason, plan.ok ? 'hint' : 'refusal');
      } else {
        const taken = graph.leaving.get(portKey(current.node, current.port));
        canvas.say(
          taken
            ? `${canvas.nameOf(graph.byId.get(current.node)!)} already feeds ${canvas.nameOf(graph.byId.get(taken.to)!)}; fan-out is not allowed.`
            : 'Release on empty canvas to add a node here.',
          taken ? 'refusal' : 'hint',
        );
      }
      return;
    }
    const port = under?.closest<HTMLElement>('[data-port-out]');
    if (!port) {
      canvas.say('Drop the wire on a free output port (the dot on a node\'s right edge).', 'hint');
      return;
    }
    const slot = slotOf(graph, port.dataset.node!, port.dataset.port!);
    const plan = slot ? planMoveChain(graph, canvas.block, current.id, slot, canvas.nameOf) : null;
    canvas.say(plan?.ok ? 'Release to move the node and the steps behind it here.' : plan ? plan.reason : '', plan?.ok ? 'hint' : 'refusal');
  }

  function up(event: PointerEvent): void {
    const current = gesture;
    if (!current) return;
    gesture = null;
    panning = false;
    if (stage?.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
    const under = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null;
    if (current.kind === 'node') {
      canvas.dragging = new Map();
      if (current.moved) canvas.place(current.id, current.at);
    } else if (current.kind === 'wire') {
      canvas.message = '';
      if (!current.moved) {
        const slot = canvas.slotFor(current.node, current.port);
        if (slot) canvas.openPalette(slot);
        return;
      }
      const node = under?.closest<HTMLElement>('[data-node]')?.dataset.node;
      if (node) {
        canvas.connectTo(current, node);
        return;
      }
      if (canvas.graph.leaving.has(portKey(current.node, current.port))) {
        const taken = canvas.graph.leaving.get(portKey(current.node, current.port))!;
        canvas.say(
          `${canvas.nameOf(canvas.graph.byId.get(current.node)!)} already feeds ${canvas.nameOf(canvas.graph.byId.get(taken.to)!)}; fan-out is not allowed.`,
        );
        return;
      }
      const slot = canvas.slotFor(current.node, current.port);
      if (slot) canvas.openPalette(slot);
    } else if (current.kind === 'pickup') {
      canvas.message = '';
      if (!current.moved) return;
      const port = under?.closest<HTMLElement>('[data-port-out]');
      if (port) canvas.moveChain(current.id, { node: port.dataset.node!, port: port.dataset.port! });
    }
  }

  function cancel(): void {
    gesture = null;
    panning = false;
    canvas.dragging = new Map();
    canvas.message = '';
  }

  // ---------------------------------------------------------------- keyboard

  function typing(target: EventTarget | null): boolean {
    const node = target as HTMLElement | null;
    return !!node && (node.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(node.tagName));
  }

  function keydown(event: KeyboardEvent): void {
    if (!active) return;
    const port = (event.target as HTMLElement).closest<HTMLElement>('[data-port-out]');
    if (port && (event.key === 'Enter' || event.key === ' ')) {
      // a focused port: the keyboard's way to insert a node there
      event.preventDefault();
      const slot = canvas.slotFor(port.dataset.node!, port.dataset.port!);
      if (slot) canvas.openPalette(slot);
      return;
    }
    if (event.key === ' ' && !typing(event.target) && !(event.target as HTMLElement | null)?.closest('button, a, [role="button"]')) {
      event.preventDefault();
      spaceHeld = true;
      return;
    }
    if (typing(event.target) || canvas.palette || event.ctrlKey || event.metaKey || event.altKey) return;
    const key = event.key;
    if (key === '/') {
      event.preventDefault();
      canvas.openPalette();
    } else if (key === 'f' || key === 'F') {
      event.preventDefault();
      canvas.frame();
    } else if (key === '0') {
      canvas.fit();
    } else if (key === '1') {
      canvas.actualSize();
    } else if (key === '+' || key === '=') {
      canvas.zoomStep(1);
    } else if (key === '-') {
      canvas.zoomStep(-1);
    } else if (key === 'Delete' || key === 'Backspace') {
      if (canvas.selection) {
        event.preventDefault();
        canvas.remove();
      }
    } else if (key === 'Escape') {
      canvas.select(null);
    } else if (key === 'Enter') {
      const input = (event.target as HTMLElement).closest('[data-node]')?.querySelector<HTMLInputElement>('input.value');
      if (input && !(event.target as HTMLElement).closest('[data-port-out]')) {
        event.preventDefault();
        input.focus();
        input.select();
      }
    } else if (key.startsWith('Arrow')) {
      event.preventDefault();
      followFocus = true;
      canvas.navigate(key === 'ArrowLeft' ? 'left' : key === 'ArrowRight' ? 'right' : key === 'ArrowUp' ? 'up' : 'down');
    }
  }

  function keyup(event: KeyboardEvent): void {
    if (event.key === ' ') spaceHeld = false;
  }

  function closePalette(): void {
    canvas.closePalette();
    stage?.focus({ preventScroll: true });
  }

  // ---------------------------------------------------------------- drawing

  const view = $derived(canvas.view);
  const worldStyle = $derived(`transform:translate(${view.panX}px,${view.panY}px) scale(${view.zoom})`);
  const gridStyle = $derived(
    `background-size:${24 * view.zoom}px ${24 * view.zoom}px;background-position:${view.panX}px ${view.panY}px`,
  );
  const selectedId = $derived(canvas.selection);
  const tabbable = $derived(selectedId ?? canvas.graph.nodes[0]?.id ?? null);

  /** The loose end of a wire being dragged: from the port to the pointer, or from the pointer to a lifted node's input. */
  const ghost = $derived.by(() => {
    const current = gesture;
    if (!current || (current.kind !== 'wire' && current.kind !== 'pickup') || !current.moved) return null;
    const graph = canvas.graph;
    const rects = canvas.placed.rects;
    if (current.kind === 'wire') {
      const node = graph.byId.get(current.node);
      if (!node || !rects.has(current.node)) return null;
      return { points: route(outputAnchor(graph, rects, current.node, current.port), current.pointer), color: canvas.colorOf(node) };
    }
    const feeder = graph.feeder.get(current.id);
    const source = feeder && graph.byId.get(feeder.from);
    if (!source || !rects.has(current.id)) return null;
    return { points: route(current.pointer, inputAnchor(rects, current.id)), color: canvas.colorOf(source) };
  });
</script>

<!-- A pointer-gesture surface by nature (like the Design canvas); every gesture has a keyboard route (arrows, /, Enter on a port, Delete, F). -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
  class="stage"
  class:panning
  class:spare={spaceHeld}
  bind:this={stage}
  role="application"
  aria-label="Logic canvas"
  aria-describedby="logic-canvas-help"
  data-testid="logic-canvas"
  tabindex="-1"
  style={gridStyle}
  onpointerdown={down}
  onpointermove={move}
  onpointerup={up}
  onpointercancel={cancel}
  onkeydown={keydown}
  onkeyup={keyup}
>
  <p id="logic-canvas-help" class="sr">
    Arrow keys move between nodes. Enter edits a node's value, or inserts a node at a focused port. Slash opens the palette.
    Delete removes the selected node. F frames it. Control Z undoes.
  </p>

  <div class="world" role="tree" aria-label="Nodes" class:glide={canvas.glide} style={worldStyle} ontransitionend={() => (canvas.glide = false)}>
    <svg class="wires" width="1" height="1" aria-hidden="true">
      {#each canvas.routes as wire (wire.edge.id)}
        {@const source = canvas.graph.byId.get(wire.edge.from)!}
        {@const end = wire.points[wire.points.length - 1]!}
        <g
          class="wire"
          class:lit={selectedId === wire.edge.from || selectedId === wire.edge.to}
          style="--w:{canvas.colorOf(source)}"
          data-wire={wire.edge.id}
        >
          <path class="shade" d={pathOf(wire.points)} />
          <path class="line" d={pathOf(wire.points)} />
          {#each wire.points.slice(1, -1) as corner, index (index)}
            <rect class="dust" x={corner.x - 3} y={corner.y - 3} width="6" height="6" />
          {/each}
          <rect class="tip" x={end.x - 6} y={end.y - 3} width="6" height="6" />
        </g>
      {/each}
      {#if ghost}
        <g class="wire ghost" style="--w:{ghost.color}">
          <path class="shade" d={pathOf(ghost.points)} />
          <path class="line" d={pathOf(ghost.points)} />
        </g>
      {/if}
    </svg>

    {#each canvas.graph.nodes as node (node.id)}
      {@const rect = canvas.placed.rects.get(node.id)}
      {#if rect}
        <LogicNode {node} {rect} {canvas} selected={selectedId === node.id} tab={tabbable === node.id} />
      {/if}
    {/each}
  </div>

  {#if canvas.graph.nodes.length === 0}
    <div class="overlay" data-testid="logic-empty">
      <Icon name="workflow" size={28} />
      <h3>No logic yet</h3>
      <p>A rule starts with a trigger: when something happens, steps run.</p>
      <button type="button" class="start" onclick={() => canvas.openPalette()}>Add a trigger <kbd>/</kbd></button>
    </div>
  {/if}

  {#if canvas.palette}
    <LogicPalette {canvas} onclose={closePalette} />
  {/if}
</div>

<style>
  .stage {
    position: relative;
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    background-color: var(--logic-stage);
    background-image: radial-gradient(circle, var(--logic-grid) 1.2px, transparent 1.6px);
    touch-action: none;
    cursor: default;
    outline: none;
  }
  .stage.spare {
    cursor: grab;
  }
  .stage.panning {
    cursor: grabbing;
  }
  .stage:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: -2px;
  }
  .world {
    position: absolute;
    left: 0;
    top: 0;
    width: 0;
    height: 0;
    transform-origin: 0 0;
  }
  .world.glide {
    transition: transform var(--motion-base) var(--ease-emphasized);
  }
  .wires {
    position: absolute;
    left: 0;
    top: 0;
    overflow: visible;
    pointer-events: none;
  }
  .wire .shade {
    fill: none;
    stroke: var(--logic-wire-shade);
    stroke-width: 7;
    stroke-linecap: square;
    stroke-linejoin: miter;
    shape-rendering: crispEdges;
  }
  .wire .line {
    fill: none;
    stroke: color-mix(in srgb, var(--w) 70%, var(--logic-stage));
    stroke-width: 3;
    stroke-linecap: square;
    stroke-linejoin: miter;
    shape-rendering: crispEdges;
  }
  .wire .dust,
  .wire .tip {
    fill: var(--w);
    shape-rendering: crispEdges;
  }
  .wire.lit .line {
    stroke: var(--w);
    stroke-width: 4;
  }
  .wire.ghost .line {
    stroke: var(--w);
    stroke-dasharray: 8 6;
  }
  @media (prefers-reduced-motion: no-preference) {
    .wire.ghost .line {
      animation: march 600ms linear infinite;
    }
  }
  @keyframes march {
    to {
      stroke-dashoffset: -14;
    }
  }
  .overlay {
    position: absolute;
    left: 50%;
    top: 50%;
    display: grid;
    justify-items: center;
    gap: var(--space-2);
    max-width: calc(100% - var(--space-8));
    transform: translate(-50%, -50%);
    color: var(--muted);
    text-align: center;
  }
  .overlay h3 {
    margin: 0;
    color: var(--fg);
    font-size: var(--text-lg);
  }
  .overlay p {
    margin: 0;
    font-size: var(--text-sm);
  }
  .start {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: var(--space-2);
    padding: var(--space-1) var(--space-4);
    background: var(--accent);
    border: 0;
    border-radius: var(--radius);
    color: var(--accent-ink);
    font: 600 var(--text-sm) var(--font-sans);
    cursor: pointer;
  }
  kbd {
    padding: 0 var(--space-1);
    border: 1px solid currentColor;
    border-radius: var(--radius-sm);
    font: var(--text-xs) var(--mono);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
