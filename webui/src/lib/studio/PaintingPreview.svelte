<script lang="ts">
  /**
   * A painting on a wall at true block scale (one block = 16 texture pixels =
   * one grid cell), with a handle in its corner: drag it to resize in whole
   * blocks (1..16), or focus it and use the arrow keys. The numeric width and
   * height fields stay the exact fallback.
   */
  import { assetOps, unwrap } from '../api';
  import { session } from '../state.svelte';

  interface Props {
    pack: string;
    texture: string;
    width: number;
    height: number;
    /** Bumped when the pixel editor writes, so the preview re-reads the bytes. */
    nonce?: number;
    /** Called with the new size in blocks while the handle moves. */
    onResize?: (width: number, height: number) => void;
  }

  let { pack, texture, width, height, nonce = 0, onResize }: Props = $props();

  /** CSS pixels per block on the wall: 16 = the texture at 1:1, 16 blocks fill the aside. */
  const CELL = 16;
  const MAX = 16;

  let image = $state('');
  let dragging = $state(false);
  let wall: HTMLDivElement | undefined = $state();

  const shown = $derived(texture || '');
  const w = $derived(Math.min(MAX, Math.max(1, Math.round(width) || 1)));
  const h = $derived(Math.min(MAX, Math.max(1, Math.round(height) || 1)));
  /** The wall always has room to grow: one spare block beside and below. */
  const cols = MAX;
  const rows = $derived(Math.min(MAX, Math.max(8, h + 1)));

  $effect(() => {
    void resolve(shown, nonce);
  });

  async function resolve(reference: string, _nonce: number) {
    image = '';
    if (!reference || !session.channel) return;
    const cleaned = reference.replace(/\.png$/i, '');
    const path = cleaned.includes('/')
      ? `assets/textures/${cleaned}.png`
      : `assets/textures/painting/${cleaned}.png`;
    try {
      const payload = unwrap(await assetOps.get(session.channel, pack, path));
      image = `data:image/png;base64,${String(payload.data)}`;
    } catch {
      image = '';
    }
  }

  function clamp(value: number): number {
    return Math.min(MAX, Math.max(1, value));
  }

  function resize(nextWidth: number, nextHeight: number) {
    const a = clamp(nextWidth);
    const b = clamp(nextHeight);
    if (a !== w || b !== h) onResize?.(a, b);
  }

  function pointerDown(event: PointerEvent) {
    if (!onResize) return;
    dragging = true;
    (event.currentTarget as Element).setPointerCapture(event.pointerId);
    event.preventDefault();
  }

  function pointerMove(event: PointerEvent) {
    if (!dragging || !wall) return;
    const box = wall.getBoundingClientRect();
    // The painting is anchored at the wall's top-left block: the pointer's block cell is its far corner.
    resize(Math.ceil((event.clientX - box.left) / CELL), Math.ceil((event.clientY - box.top) / CELL));
  }

  function pointerUp() {
    dragging = false;
  }

  function key(event: KeyboardEvent) {
    const dx = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    const dy = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (!dx && !dy) return;
    event.preventDefault();
    resize(w + dx, h + dy);
  }
</script>

<div class="preview">
  <div class="wall" bind:this={wall} data-wall
    style="width: {cols * CELL}px; height: {rows * CELL}px; background-size: {CELL}px {CELL}px, {CELL}px {CELL}px, auto;">
    <div class="painting" style="width: {w * CELL}px; height: {h * CELL}px;">
      {#if image}
        <img src={image} alt="painting texture" />
      {:else}
        <span class="empty">no texture</span>
      {/if}
      <span class="handle" role="slider" tabindex="0" aria-label="Painting size in blocks"
        aria-valuemin="1" aria-valuemax={MAX} aria-valuenow={w} aria-valuetext={`${w} by ${h} blocks`}
        onpointerdown={pointerDown} onpointermove={pointerMove} onpointerup={pointerUp}
        onpointercancel={pointerUp} onkeydown={key}></span>
    </div>
  </div>
  <p class="hint">Wall at true block scale (16 px per block). Drag the corner handle or use the arrow keys to resize.</p>
  <dl>
    <dt>size</dt>
    <dd>{w} × {h} blocks · {w * 16} × {h * 16} px</dd>
    <dt>texture</dt>
    <dd title={shown}>{shown || '—'}</dd>
  </dl>
</div>

<style>
  .preview {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
  }
  .wall {
    position: relative;
    max-width: 100%;
    overflow: hidden;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background:
      linear-gradient(var(--canvas-grid) 1px, transparent 1px),
      linear-gradient(90deg, var(--canvas-grid) 1px, transparent 1px),
      var(--bg-raised);
    touch-action: none;
  }
  .painting {
    position: relative;
    display: grid;
    place-items: center;
    background: var(--bg-input);
    outline: 1px solid var(--accent);
  }
  .painting img {
    width: 100%;
    height: 100%;
    object-fit: fill;
    image-rendering: pixelated;
  }
  .handle {
    position: absolute;
    right: -5px;
    bottom: -5px;
    width: 10px;
    height: 10px;
    background: var(--accent);
    border: 1px solid var(--bg-app);
    border-radius: var(--radius-sm);
    cursor: nwse-resize;
  }
  .handle:focus-visible {
    outline: 2px solid var(--focus-ring);
  }
  .empty {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .hint {
    margin: 0;
    color: var(--faint);
    font-size: var(--text-xs);
  }
  dl {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-half) var(--space-2);
    margin: 0;
    font-size: var(--text-xs);
  }
  dt {
    color: var(--faint);
  }
  dd {
    margin: 0;
    font-family: var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
