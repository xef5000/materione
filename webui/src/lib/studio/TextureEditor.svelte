<script lang="ts">
  /**
   * A pixel editor for pack textures. It exists so that "change three pixels"
   * does not mean "boot a 3D modelling application": it opens in place over
   * the studio, edits the PNG on disk through `assets.put`, and gets out of
   * the way. All drawing rules live in `pixels.ts` and are unit-tested; this
   * component only turns pointer events into calls and paints the result.
   */
  import { assetOps, unwrap } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';
  import {
    History,
    cloneBitmap,
    colorToHex,
    createBitmap,
    fill,
    getPixel,
    hexToColor,
    line,
    rectangle,
    resize,
    stamp,
    toPixel,
    usedColors,
    type Bitmap,
    type Color,
  } from '../pixels';

  interface Props {
    pack: string;
    /** Pack-relative PNG path; created if it does not exist yet. */
    path: string;
    onClose: () => void;
    onSaved?: () => void;
  }

  let { pack, path, onClose, onSaved }: Props = $props();

  type Tool = 'pencil' | 'eraser' | 'fill' | 'pick' | 'line' | 'rect';

  const TOOLS: { id: Tool; glyph: string; label: string; key: string }[] = [
    { id: 'pencil', glyph: '✏', label: 'Pencil', key: 'B' },
    { id: 'eraser', glyph: '⌫', label: 'Eraser', key: 'E' },
    { id: 'fill', glyph: '▧', label: 'Fill', key: 'G' },
    { id: 'pick', glyph: '⊙', label: 'Pick colour', key: 'I' },
    { id: 'line', glyph: '╱', label: 'Line', key: 'L' },
    { id: 'rect', glyph: '▭', label: 'Rectangle', key: 'R' },
  ];

  let bitmap = $state<Bitmap>(createBitmap(16, 16));
  let tool = $state<Tool>('pencil');
  let hex = $state('#c94f4f');
  let alpha = $state(255);
  let size = $state(1);
  let mirror = $state(false);
  let filled = $state(false);
  let zoom = $state(16);
  let pan = $state({ x: 0, y: 0 });
  let dirty = $state(false);
  let saving = $state(false);
  let error = $state('');
  let missing = $state(false);
  let recents = $state<string[]>([]);
  let cursor = $state<{ x: number; y: number } | null>(null);
  // A shape in progress is drawn on a copy so it can follow the pointer.
  let anchor: { x: number; y: number } | null = null;
  let base: Bitmap | null = null;
  let painting = false;
  let last: { x: number; y: number } | null = null;
  let panning = false;
  let panFrom = { x: 0, y: 0, panX: 0, panY: 0 };

  const history = new History();
  let canvas = $state<HTMLCanvasElement | null>(null);

  const color = $derived(hexToColor(hex, alpha));
  const palette = $derived(usedColors(bitmap, 12).map(colorToHex));
  const name = $derived(path.split('/').pop() ?? path);

  $effect(() => {
    void load(path);
  });

  // Repaint whenever the bitmap, the view or the hover changes.
  $effect(() => {
    paint(bitmap, zoom, pan, cursor);
  });

  async function load(target: string) {
    error = '';
    missing = false;
    try {
      const payload = unwrap(await assetOps.get(session.channel!, pack, target));
      bitmap = await decode(String(payload.data));
    } catch (e) {
      const message = errorMessage(e);
      if (message.includes('MTR-0009') || message.toLowerCase().includes('not found')) {
        // A texture that does not exist yet is a blank one, not an error.
        bitmap = createBitmap(16, 16);
        missing = true;
        dirty = true;
      } else {
        error = message;
      }
    }
    history.clear();
    fitToView();
  }

  function decode(base64: string): Promise<Bitmap> {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => {
        const scratch = document.createElement('canvas');
        scratch.width = image.naturalWidth;
        scratch.height = image.naturalHeight;
        const context = scratch.getContext('2d')!;
        context.drawImage(image, 0, 0);
        const data = context.getImageData(0, 0, scratch.width, scratch.height);
        resolve({
          width: scratch.width,
          height: scratch.height,
          data: new Uint8ClampedArray(data.data),
        });
      };
      image.onerror = () => reject(new Error('not a readable image'));
      image.src = `data:image/png;base64,${base64}`;
    });
  }

  function fitToView() {
    const box = canvas?.parentElement;
    if (!box) return;
    const scale = Math.max(
      1,
      Math.floor(Math.min(box.clientWidth / bitmap.width, box.clientHeight / bitmap.height)) - 1,
    );
    zoom = scale;
    pan = {
      x: Math.round((box.clientWidth - bitmap.width * scale) / 2),
      y: Math.round((box.clientHeight - bitmap.height * scale) / 2),
    };
  }

  function paint(
    source: Bitmap,
    scale: number,
    offset: { x: number; y: number },
    hover: { x: number; y: number } | null,
  ) {
    const element = canvas;
    if (!element) return;
    const box = element.parentElement!;
    element.width = box.clientWidth;
    element.height = box.clientHeight;
    const context = element.getContext('2d')!;
    context.imageSmoothingEnabled = false;
    context.clearRect(0, 0, element.width, element.height);

    // Checkerboard, so transparency reads as transparency and not as white.
    const square = Math.max(4, scale);
    for (let y = 0; y < source.height * scale; y += square) {
      for (let x = 0; x < source.width * scale; x += square) {
        context.fillStyle = ((x / square + y / square) | 0) % 2 === 0 ? '#1a1e26' : '#151920';
        context.fillRect(offset.x + x, offset.y + y, square, square);
      }
    }

    const scratch = document.createElement('canvas');
    scratch.width = source.width;
    scratch.height = source.height;
    scratch
      .getContext('2d')!
      .putImageData(new ImageData(source.data, source.width, source.height), 0, 0);
    context.drawImage(
      scratch,
      offset.x,
      offset.y,
      source.width * scale,
      source.height * scale,
    );

    if (scale >= 8) {
      context.strokeStyle = 'rgba(255,255,255,0.06)';
      context.lineWidth = 1;
      context.beginPath();
      for (let x = 0; x <= source.width; x++) {
        context.moveTo(offset.x + x * scale + 0.5, offset.y);
        context.lineTo(offset.x + x * scale + 0.5, offset.y + source.height * scale);
      }
      for (let y = 0; y <= source.height; y++) {
        context.moveTo(offset.x, offset.y + y * scale + 0.5);
        context.lineTo(offset.x + source.width * scale, offset.y + y * scale + 0.5);
      }
      context.stroke();
    }

    if (hover) {
      context.strokeStyle = 'rgba(255,255,255,0.75)';
      context.strokeRect(
        offset.x + hover.x * scale + 0.5,
        offset.y + hover.y * scale + 0.5,
        scale - 1,
        scale - 1,
      );
    }
  }

  function brushOf(erase: boolean) {
    return { color: erase ? ([0, 0, 0, 0] as Color) : color, size, mirrorX: mirror };
  }

  function pointOf(event: PointerEvent) {
    const rect = canvas!.getBoundingClientRect();
    return toPixel(
      { x: event.clientX - rect.left, y: event.clientY - rect.top },
      { zoom, panX: pan.x, panY: pan.y },
    );
  }

  function onPointerDown(event: PointerEvent) {
    if (event.button === 1 || event.shiftKey) {
      panning = true;
      panFrom = { x: event.clientX, y: event.clientY, panX: pan.x, panY: pan.y };
      canvas!.setPointerCapture(event.pointerId);
      return;
    }
    const point = pointOf(event);
    if (tool === 'pick') {
      const picked = getPixel(bitmap, point.x, point.y);
      hex = colorToHex(picked);
      alpha = picked[3];
      return;
    }
    history.push(bitmap);
    canvas!.setPointerCapture(event.pointerId);
    if (tool === 'line' || tool === 'rect') {
      anchor = point;
      base = cloneBitmap(bitmap);
      return;
    }
    painting = true;
    const next = cloneBitmap(bitmap);
    if (tool === 'fill') fill(next, point.x, point.y, color);
    else stamp(next, point.x, point.y, brushOf(tool === 'eraser'));
    last = point;
    bitmap = next;
    dirty = true;
  }

  function onPointerMove(event: PointerEvent) {
    if (panning) {
      pan = {
        x: panFrom.panX + (event.clientX - panFrom.x),
        y: panFrom.panY + (event.clientY - panFrom.y),
      };
      return;
    }
    const point = pointOf(event);
    cursor = point;
    if (anchor && base) {
      const preview = cloneBitmap(base);
      if (tool === 'line') line(preview, anchor.x, anchor.y, point.x, point.y, brushOf(false));
      else rectangle(preview, anchor.x, anchor.y, point.x, point.y, brushOf(false), filled);
      bitmap = preview;
      return;
    }
    if (!painting || !last) return;
    const next = cloneBitmap(bitmap);
    line(next, last.x, last.y, point.x, point.y, brushOf(tool === 'eraser'));
    last = point;
    bitmap = next;
    dirty = true;
  }

  function onPointerUp() {
    if (anchor) dirty = true;
    painting = false;
    panning = false;
    anchor = null;
    base = null;
    last = null;
  }

  function onWheel(event: WheelEvent) {
    event.preventDefault();
    const rect = canvas!.getBoundingClientRect();
    const focus = { x: event.clientX - rect.left, y: event.clientY - rect.top };
    const before = toPixel(focus, { zoom, panX: pan.x, panY: pan.y });
    const next = Math.min(48, Math.max(1, zoom + (event.deltaY < 0 ? 1 : -1) * Math.max(1, zoom / 8)));
    zoom = Math.round(next);
    pan = { x: focus.x - before.x * zoom, y: focus.y - before.y * zoom };
  }

  function undo() {
    const previous = history.undo(bitmap);
    if (previous) {
      bitmap = previous;
      dirty = true;
    }
  }

  function redo() {
    const next = history.redo(bitmap);
    if (next) {
      bitmap = next;
      dirty = true;
    }
  }

  function setSize(width: number, height: number) {
    history.push(bitmap);
    bitmap = resize(bitmap, width, height);
    dirty = true;
    fitToView();
  }

  function encode(): Promise<Uint8Array> {
    const scratch = document.createElement('canvas');
    scratch.width = bitmap.width;
    scratch.height = bitmap.height;
    scratch
      .getContext('2d')!
      .putImageData(new ImageData(bitmap.data, bitmap.width, bitmap.height), 0, 0);
    return new Promise((resolve, reject) => {
      scratch.toBlob(async (blob) => {
        if (!blob) return reject(new Error('could not encode PNG'));
        resolve(new Uint8Array(await blob.arrayBuffer()));
      }, 'image/png');
    });
  }

  async function save() {
    saving = true;
    try {
      unwrap(await assetOps.put(session.channel!, pack, path, await encode()));
      remember(hex);
      dirty = false;
      missing = false;
      logActivity('save', `${path} saved; targeted reload queued`);
      onSaved?.();
    } catch (e) {
      error = errorMessage(e);
    } finally {
      saving = false;
    }
  }

  function remember(value: string) {
    recents = [value, ...recents.filter((entry) => entry !== value)].slice(0, 8);
  }

  async function importPng(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    bytes.forEach((byte) => (binary += String.fromCharCode(byte)));
    history.push(bitmap);
    bitmap = await decode(btoa(binary));
    dirty = true;
    fitToView();
  }

  async function exportPng() {
    const blob = new Blob([await encode()], { type: 'image/png' });
    const url = URL.createObjectURL(blob);
    const anchorElement = document.createElement('a');
    anchorElement.href = url;
    anchorElement.download = name;
    anchorElement.click();
    URL.revokeObjectURL(url);
  }

  function close() {
    if (dirty && !confirm('Close without saving this texture?')) return;
    onClose();
  }

  function onKeydown(event: KeyboardEvent) {
    const typing = (event.target as HTMLElement)?.tagName === 'INPUT';
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
      event.preventDefault();
      event.shiftKey ? redo() : undo();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      void save();
      return;
    }
    if (typing || event.ctrlKey || event.metaKey) return;
    if (event.key === 'Escape') close();
    const match = TOOLS.find((entry) => entry.key.toLowerCase() === event.key.toLowerCase());
    if (match) tool = match.id;
  }
</script>

<svelte:window onkeydown={onKeydown} onresize={fitToView} />

<div class="texture-editor">
  <header>
    <span class="title" title={path}>{name}</span>
    <span class="dims">{bitmap.width}×{bitmap.height}</span>
    {#if missing}<span class="new">new</span>{/if}
    <span class="spacer"></span>
    <button type="button" onclick={undo} disabled={!history.canUndo} title="undo (Ctrl+Z)">↶</button>
    <button type="button" onclick={redo} disabled={!history.canRedo} title="redo (Ctrl+Shift+Z)">
      ↷
    </button>
    <label class="ghost" title="replace with a PNG">
      Import
      <input type="file" accept="image/png" onchange={importPng} hidden />
    </label>
    <button type="button" class="ghost" onclick={exportPng}>Export</button>
    <button type="button" class="ghost" onclick={close}>Close</button>
    <button type="button" class="primary" onclick={save} disabled={!dirty || saving}>
      {saving ? 'Saving…' : 'Save'}
    </button>
  </header>

  {#if error}<p class="error">{error}</p>{/if}

  <div class="work">
    <aside class="tools">
      {#each TOOLS as entry (entry.id)}
        <button
          type="button"
          class:active={tool === entry.id}
          title="{entry.label} ({entry.key})"
          onclick={() => (tool = entry.id)}
        >
          {entry.glyph}
        </button>
      {/each}
      <button
        type="button"
        class:active={mirror}
        title="mirror across the vertical axis"
        onclick={() => (mirror = !mirror)}
      >
        ⇔
      </button>
      {#if tool === 'rect'}
        <button type="button" class:active={filled} title="filled" onclick={() => (filled = !filled)}>
          ■
        </button>
      {/if}
    </aside>

    <div class="stage">
      <canvas
        bind:this={canvas}
        onpointerdown={onPointerDown}
        onpointermove={onPointerMove}
        onpointerup={onPointerUp}
        onpointerleave={() => (cursor = null)}
        onwheel={onWheel}
      ></canvas>
    </div>

    <aside class="colors">
      <input type="color" bind:value={hex} onchange={() => remember(hex)} title="colour" />
      <input type="text" class="hex" bind:value={hex} spellcheck="false" />
      <label class="range" title="alpha">
        α
        <input type="range" min="0" max="255" bind:value={alpha} />
      </label>
      <label class="range" title="brush size">
        ⬛
        <input type="range" min="1" max="8" bind:value={size} />
      </label>

      {#if recents.length > 0}
        <span class="group">Recent</span>
        <div class="swatches">
          {#each recents as swatch (swatch)}
            <button
              type="button"
              style="background:{swatch}"
              title={swatch}
              aria-label={swatch}
              onclick={() => (hex = swatch)}
            ></button>
          {/each}
        </div>
      {/if}

      {#if palette.length > 0}
        <span class="group">In this texture</span>
        <div class="swatches">
          {#each palette as swatch (swatch)}
            <button
              type="button"
              style="background:{swatch}"
              title={swatch}
              aria-label={swatch}
              onclick={() => (hex = swatch)}
            ></button>
          {/each}
        </div>
      {/if}

      <span class="group">Size</span>
      <div class="sizes">
        {#each [16, 32, 64] as preset (preset)}
          <button
            type="button"
            class:active={bitmap.width === preset && bitmap.height === preset}
            onclick={() => setSize(preset, preset)}
          >
            {preset}
          </button>
        {/each}
      </div>
    </aside>
  </div>
</div>

<style>
  .texture-editor {
    display: grid;
    grid-template-rows: auto auto minmax(0, 1fr);
    height: 100%;
    min-width: 0;
    background: var(--bg-app);
  }
  header {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .title {
    font-size: 13px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .dims,
  .new {
    font: 11px var(--mono);
    color: var(--faint);
    white-space: nowrap;
  }
  .new {
    color: var(--warn);
  }
  .spacer {
    flex: 1;
    min-width: 0;
  }
  header button,
  header .ghost {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: 3px 9px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  header button:disabled {
    color: var(--faint);
    border-color: var(--border);
    cursor: default;
  }
  header .primary {
    background: var(--primary);
    border-color: transparent;
    color: #fff;
  }
  header .primary:disabled {
    background: none;
  }
  .work {
    display: grid;
    grid-template-columns: 36px minmax(0, 1fr) minmax(0, 168px);
    min-width: 0;
    overflow: hidden;
  }
  .tools {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 6px 4px;
    border-right: 1px solid var(--border);
  }
  .tools button {
    height: 28px;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--radius);
    color: var(--muted);
    font-size: 14px;
    cursor: pointer;
  }
  .tools button.active {
    background: var(--bg-raised);
    border-color: var(--border-strong);
    color: var(--fg);
  }
  .stage {
    position: relative;
    overflow: hidden;
    min-width: 0;
  }
  canvas {
    display: block;
    touch-action: none;
    cursor: crosshair;
  }
  .colors {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px;
    border-left: 1px solid var(--border);
    overflow-y: auto;
    overflow-x: hidden;
    min-width: 0;
  }
  .colors input[type='color'] {
    width: 100%;
    height: 32px;
    padding: 0;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: none;
  }
  .hex {
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: inherit;
    font: 12px var(--mono);
    padding: 4px 6px;
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
  .range {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    color: var(--faint);
    min-width: 0;
  }
  .range input {
    flex: 1;
    min-width: 0;
  }
  .group {
    font-size: 10px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
    margin-top: 4px;
  }
  .swatches {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(20px, 1fr));
    gap: 4px;
  }
  .swatches button {
    aspect-ratio: 1;
    border: 1px solid var(--border-strong);
    border-radius: 3px;
    cursor: pointer;
    padding: 0;
  }
  .sizes {
    display: flex;
    gap: 4px;
  }
  .sizes button {
    flex: 1;
    min-width: 0;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    font-size: 11px;
    padding: 3px 0;
    cursor: pointer;
  }
  .sizes button.active {
    color: var(--fg);
    background: var(--bg-raised);
  }
  .error {
    margin: 0;
    padding: 6px 10px;
    background: var(--error-bg);
    color: var(--error);
    font-size: 12px;
  }
</style>
