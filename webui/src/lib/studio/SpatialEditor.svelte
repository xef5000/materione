<script lang="ts">
  /**
   * Seats, hitboxes and lights edited on a picture of the furniture's space
   * instead of as numbers (issue 153). The schema's nested shape decides what
   * a row is: `width`+`height` makes it a box, `level` a light, anything else
   * a seat-style point — the editor never names a field. Two block-scale
   * views (front: x/y, top: x/z) show every row; dragging or arrow-keying a
   * handle rewrites that row's `offset`. The numeric rows below stay the exact
   * fallback and the only way to type a value.
   */
  import StructuredList from './StructuredList.svelte';
  import type { TypeParam } from '../api';
  import { rowsOf, toOffset, kindOf, snap, type SpatialRow } from '../spatial';

  interface Props {
    field: TypeParam;
    value: unknown;
    onChange: (value: unknown) => void;
  }

  let { field, value, onChange }: Props = $props();

  /** Pixels per block in both views. */
  const SCALE = 48;
  const FRONT = { minX: -2, maxX: 2, minY: -0.5, maxY: 2.5 };
  const TOP = { minX: -2, maxX: 2, minZ: -2, maxZ: 2 };

  const kind = $derived(kindOf(field));
  const rows = $derived(rowsOf(value));
  let selected = $state(-1);
  let drag = $state<{ index: number; view: 'front' | 'top' } | null>(null);

  const frontW = (FRONT.maxX - FRONT.minX) * SCALE;
  const frontH = (FRONT.maxY - FRONT.minY) * SCALE;
  const topW = (TOP.maxX - TOP.minX) * SCALE;
  const topH = (TOP.maxZ - TOP.minZ) * SCALE;

  const fx = (x: number) => (x - FRONT.minX) * SCALE;
  const fy = (y: number) => (FRONT.maxY - y) * SCALE;
  const tx = (x: number) => (x - TOP.minX) * SCALE;
  const tz = (z: number) => (z - TOP.minZ) * SCALE;

  function move(index: number, view: 'front' | 'top', a: number, b: number) {
    const row = rows[index];
    if (!row) return;
    const [x, y, z] = row.offset;
    const next = view === 'front' ? [a, b, z] : [a, y, b];
    if (next[0] === x && next[1] === y && next[2] === z) return;
    onChange(rows.map((entry, position) =>
      position === index ? { ...entry.raw, offset: toOffset(next) } : entry.raw));
  }

  function pointer(event: PointerEvent, view: 'front' | 'top'): [number, number] {
    const svg = (event.currentTarget as Element).closest('svg')!;
    const box = svg.getBoundingClientRect();
    const px = (event.clientX - box.left) / box.width;
    const py = (event.clientY - box.top) / box.height;
    return view === 'front'
      ? [snap(FRONT.minX + px * (FRONT.maxX - FRONT.minX)), snap(FRONT.maxY - py * (FRONT.maxY - FRONT.minY))]
      : [snap(TOP.minX + px * (TOP.maxX - TOP.minX)), snap(TOP.minZ + py * (TOP.maxZ - TOP.minZ))];
  }

  function down(event: PointerEvent, index: number, view: 'front' | 'top') {
    selected = index;
    drag = { index, view };
    (event.currentTarget as Element).setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function dragMove(event: PointerEvent, index: number, view: 'front' | 'top') {
    if (!drag || drag.index !== index || drag.view !== view) return;
    const [a, b] = pointer(event, view);
    move(index, view, a, b);
  }

  function release() {
    drag = null;
  }

  function key(event: KeyboardEvent, index: number, view: 'front' | 'top') {
    const row = rows[index];
    if (!row) return;
    const step = event.shiftKey ? 0.25 : 0.05;
    const dx = event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0;
    // Up is +y in the front view and -z (north) in the top view, as the picture shows it.
    const up = event.key === 'ArrowUp' ? step : event.key === 'ArrowDown' ? -step : 0;
    if (!dx && !up) return;
    event.preventDefault();
    const [x, y, z] = row.offset;
    if (view === 'front') move(index, 'front', snap(x + dx), snap(y + up));
    else move(index, 'top', snap(x + dx), snap(z - up));
  }

  function describe(row: SpatialRow, index: number): string {
    const [x, y, z] = row.offset;
    return `${field.label || field.id} ${index + 1} at x ${x}, y ${y}, z ${z}`;
  }
</script>

<div class="spatial" data-spatial={field.id}>
  {#if rows.length > 0}
    <div class="views">
      {#each ['front', 'top'] as view (view)}
        {@const isFront = view === 'front'}
        <figure>
          <svg viewBox={`0 0 ${isFront ? frontW : topW} ${isFront ? frontH : topH}`}
            width={isFront ? frontW : topW} height={isFront ? frontH : topH}
            role="group" aria-label={`${field.label || field.id}, ${isFront ? 'front' : 'top'} view`}
            class="view" data-view={view}>
            <!-- the 1×1 block the furniture is placed on -->
            {#if isFront}
              <rect class="block" x={fx(-0.5)} y={fy(1)} width={SCALE} height={SCALE} />
              <line class="axis" x1="0" x2={frontW} y1={fy(0)} y2={fy(0)} />
            {:else}
              <rect class="block" x={tx(-0.5)} y={tz(-0.5)} width={SCALE} height={SCALE} />
            {/if}
            {#each Array.from({ length: 5 }, (_, i) => i) as line (line)}
              {#if isFront}
                <line class="grid" x1={line * SCALE} x2={line * SCALE} y1="0" y2={frontH} />
              {:else}
                <line class="grid" x1={line * SCALE} x2={line * SCALE} y1="0" y2={topH} />
                <line class="grid" x1="0" x2={topW} y1={line * SCALE} y2={line * SCALE} />
              {/if}
            {/each}
            {#each rows as row, index (index)}
              {@const [x, y, z] = row.offset}
              {#if kind === 'box'}
                {#if isFront}
                  <rect class="shape" class:selected={selected === index} x={fx(x - row.width / 2)}
                    y={fy(y + row.height)} width={row.width * SCALE} height={row.height * SCALE} />
                {:else}
                  <rect class="shape" class:selected={selected === index} x={tx(x - row.width / 2)}
                    y={tz(z - row.width / 2)} width={row.width * SCALE} height={row.width * SCALE} />
                {/if}
              {/if}
              <circle class={`handle ${kind}`} class:selected={selected === index}
                cx={isFront ? fx(x) : tx(x)} cy={isFront ? fy(y) : tz(z)} r="8"
                role="button" tabindex="0" data-index={index} data-handle={view}
                aria-label={describe(row, index)}
                onpointerdown={(event) => down(event, index, view as 'front' | 'top')}
                onpointermove={(event) => dragMove(event, index, view as 'front' | 'top')}
                onpointerup={release} onpointercancel={release}
                onfocus={() => (selected = index)}
                onkeydown={(event) => key(event, index, view as 'front' | 'top')} />
              <text class="tag" x={(isFront ? fx(x) : tx(x)) + 11} y={(isFront ? fy(y) : tz(z)) - 9}>{index + 1}</text>
            {/each}
          </svg>
          <figcaption>{isFront ? 'Front (x, y)' : 'Top (x, z)'} · grid = 1 block</figcaption>
        </figure>
      {/each}
    </div>
    <p class="hint">
      Drag a handle or focus it and use the arrow keys (Shift = bigger steps).
      The rows below hold the exact numbers.
    </p>
  {/if}
  <StructuredList {field} {value} {onChange} compact
    addLabel={kind === 'light' ? 'Add light' : kind === 'box' ? 'Add hitbox' : 'Add seat'} />
</div>

<style>
  .spatial {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  .views {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-3);
  }
  figure {
    margin: 0;
    display: grid;
    gap: var(--space-1);
  }
  figcaption {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .view {
    max-width: 100%;
    height: auto;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    touch-action: none;
  }
  .grid {
    stroke: var(--canvas-grid);
    stroke-width: 1;
  }
  .axis {
    stroke: var(--control-line);
    stroke-width: 1;
    stroke-dasharray: 4 3;
  }
  .block {
    fill: var(--bg-raised);
    stroke: var(--border-strong);
    stroke-width: 1;
  }
  .shape {
    fill: var(--accent-low);
    stroke: var(--accent);
    stroke-width: 1;
    opacity: 0.8;
  }
  .shape.selected {
    stroke-width: 2;
  }
  .handle {
    fill: var(--accent);
    stroke: var(--bg-app);
    stroke-width: 2;
    cursor: grab;
  }
  .handle.light {
    fill: var(--warn);
  }
  .handle.selected,
  .handle:focus-visible {
    stroke: var(--fg);
    outline: none;
  }
  .tag {
    fill: var(--muted);
    font-size: var(--text-xs);
    pointer-events: none;
  }
  .hint {
    margin: 0;
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
