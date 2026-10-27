<script lang="ts">
  import { untrack } from 'svelte';
  /**
   * A pixel editor for pack textures. It exists so that "change three pixels"
   * does not mean "boot a 3D modelling application": it opens in place over
   * the studio, edits the PNG on disk through `assets.put`, and gets out of
   * the way. All drawing rules live in `pixels.ts` and are unit-tested; this
   * component only turns pointer events into calls and paints the result.
   */
  import { assetOps, RpcError, unwrap } from '../api';
  import { connection, errorMessage, logActivity, session } from '../state.svelte';
  import { sameAssetSnapshot, type AssetSnapshot } from '../studio';
  import { token } from '../tokens';
  import {
    History,
    applyRatio,
    clampSelection,
    cloneBitmap,
    colorToHex,
    createBitmap,
    crop,
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
    type Ratio,
    type Selection,
  } from '../pixels';

  interface Props {
    pack: string;
    /** Pack-relative PNG path; created if it does not exist yet. */
    path: string;
    onClose: () => void;
    onSaved?: () => void | Promise<void>;
    onDirtyChange?: (dirty: boolean) => void;
    onSavingChange?: (saving: boolean) => void;
    draftError?: string;
    active?: boolean;
  }

  let {
    pack,
    path,
    onClose,
    onSaved,
    onDirtyChange,
    onSavingChange,
    draftError = '',
    active = true,
  }: Props = $props();

  type Tool = 'pencil' | 'eraser' | 'fill' | 'pick' | 'line' | 'rect' | 'crop';

  /**
   * The right panel is "tool settings": it shows the colour controls exactly
   * when the active tool consumes a colour, and per-tool controls otherwise.
   */
  const TOOLS: {
    id: Tool;
    glyph: string;
    label: string;
    key: string;
    needsColor: boolean;
  }[] = [
    { id: 'pencil', glyph: '✏', label: 'Pencil', key: 'B', needsColor: true },
    { id: 'eraser', glyph: '⌫', label: 'Eraser', key: 'E', needsColor: false },
    { id: 'fill', glyph: '▧', label: 'Fill', key: 'G', needsColor: true },
    { id: 'pick', glyph: '⊙', label: 'Pick colour', key: 'I', needsColor: false },
    { id: 'line', glyph: '╱', label: 'Line', key: 'L', needsColor: true },
    { id: 'rect', glyph: '▭', label: 'Rectangle', key: 'R', needsColor: true },
    { id: 'crop', glyph: '⬚', label: 'Crop', key: 'C', needsColor: false },
  ];

  const PAINT_TOOLS: Tool[] = ['pencil', 'eraser', 'line', 'rect'];
  const RATIOS: Ratio[] = ['free', '1:1', '2:1', '1:2'];

  let bitmap = $state<Bitmap>(createBitmap(16, 16));
  let tool = $state<Tool>('pencil');
  let hex = $state(token('--paint-default'));
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
  let loadState = $state<'loading' | 'ready' | 'error'>('loading');
  let sourceSnapshot = $state<AssetSnapshot | null>(null);
  let loadedPack = $state('');
  let loadedPath = $state('');
  let loadToken = 0;
  let recents = $state<string[]>([]);
  let cursor = $state<{ x: number; y: number } | null>(null);
  // The crop marquee persists after the drag ends so the panel can adjust it.
  let selection = $state<Selection | null>(null);
  let ratio = $state<Ratio>('free');
  // Free canvas sizing: the inputs track the bitmap, the button applies.
  let canvasWidth = $state(16);
  let canvasHeight = $state(16);
  // A shape in progress is drawn on a copy so it can follow the pointer.
  let anchor: { x: number; y: number } | null = null;
  let base: Bitmap | null = null;
  let painting = false;
  let last: { x: number; y: number } | null = null;
  let marqueeDrag = false;
  let marqueeFrom: { x: number; y: number } | null = null;
  let panning = false;
  let panFrom = { x: 0, y: 0, panX: 0, panY: 0 };

  const history = new History();
  let canvas = $state<HTMLCanvasElement | null>(null);

  const color = $derived(hexToColor(hex, alpha));
  const palette = $derived(usedColors(bitmap, 12).map(colorToHex));
  const name = $derived(path.split('/').pop() ?? path);
  const canEdit = $derived(loadState === 'ready');
  const toolNeedsColor = $derived(
    TOOLS.find((entry) => entry.id === tool)?.needsColor ?? false,
  );

  $effect(() => {
    const targetPack = pack;
    const target = path;
    untrack(() => {
      void load(targetPack, target);
    });
  });

  $effect(() => {
    onDirtyChange?.(dirty);
  });

  $effect(() => {
    onSavingChange?.(saving);
  });

  // Repaint whenever the bitmap, the view, the hover or the marquee changes.
  $effect(() => {
    paint(bitmap, zoom, pan, cursor, selection);
  });

  // The canvas inputs mirror the bitmap until the user edits them.
  $effect(() => {
    canvasWidth = bitmap.width;
    canvasHeight = bitmap.height;
  });

  // A marquee belongs to the crop tool; switching tools dismisses it.
  $effect(() => {
    if (tool !== 'crop') selection = null;
  });

  async function load(targetPack: string, target: string) {
    const token = ++loadToken;
    const channel = session.channel;
    const connected = connection.connected;
    if (saving) {
      loadState = 'error';
      error = 'Wait for the texture write to finish before changing its asset context.';
      return;
    }
    if (dirty && loadedPack && (loadedPack !== targetPack || loadedPath !== target)) {
      loadState = 'error';
      error = 'The texture asset changed while pixels were pending. Close this editor without reloading to preserve them.';
      return;
    }
    error = '';
    missing = false;
    loadState = 'loading';
    sourceSnapshot = null;
    loadedPack = '';
    loadedPath = '';
    if (!connected || !channel) {
      loadState = 'error';
      error = 'Connect to the server before opening an immediate-write texture editor.';
      return;
    }
    try {
      const snapshot = await readSnapshot(channel, targetPack, target);
      const loadedBitmap =
        snapshot.kind === 'present' ? await decode(snapshot.data) : createBitmap(16, 16);
      if (token !== loadToken) return;
      bitmap = loadedBitmap;
      sourceSnapshot = snapshot;
      loadedPack = targetPack;
      loadedPath = target;
      missing = snapshot.kind === 'missing';
      dirty = false;
      loadState = 'ready';
    } catch (e) {
      if (token !== loadToken) return;
      loadState = 'error';
      error = `Could not read this texture; the canvas cannot be safely applied. Close and reopen after reconnecting to retry. ${errorMessage(e)}`;
    }
    if (token !== loadToken) return;
    history.clear();
    fitToView();
  }

  async function readSnapshot(
    channel: NonNullable<typeof session.channel>,
    targetPack: string,
    targetPath: string,
  ): Promise<AssetSnapshot> {
    try {
      const payload = unwrap(await assetOps.get(channel, targetPack, targetPath));
      return { kind: 'present', data: String(payload.data ?? '') };
    } catch (e) {
      if (e instanceof RpcError && e.code === 'MTR-0009') return { kind: 'missing' };
      throw e;
    }
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
    mark: Selection | null,
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
    const checkerA = token('--checker-a');
    const checkerB = token('--checker-b');
    for (let y = 0; y < source.height * scale; y += square) {
      for (let x = 0; x < source.width * scale; x += square) {
        context.fillStyle = ((x / square + y / square) | 0) % 2 === 0 ? checkerA : checkerB;
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
      context.strokeStyle = token('--canvas-grid');
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
      context.strokeStyle = token('--canvas-hover');
      context.strokeRect(
        offset.x + hover.x * scale + 0.5,
        offset.y + hover.y * scale + 0.5,
        scale - 1,
        scale - 1,
      );
    }

    if (mark) {
      // Dim everything the crop would cut away, then outline the kept region.
      const markX = offset.x + mark.x * scale;
      const markY = offset.y + mark.y * scale;
      const markW = mark.width * scale;
      const markH = mark.height * scale;
      context.fillStyle = token('--canvas-dim');
      context.beginPath();
      context.rect(0, 0, element.width, element.height);
      context.rect(markX, markY, markW, markH);
      context.fill('evenodd');
      context.strokeStyle = token('--canvas-mark');
      context.lineWidth = 1;
      context.setLineDash([4, 3]);
      context.strokeRect(markX + 0.5, markY + 0.5, markW - 1, markH - 1);
      context.setLineDash([]);
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
    if (tool === 'crop') {
      marqueeDrag = true;
      marqueeFrom = point;
      selection = clampSelection(point.x, point.y, point.x, point.y, bitmap.width, bitmap.height);
      canvas!.setPointerCapture(event.pointerId);
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
    if (marqueeDrag && marqueeFrom) {
      let next = clampSelection(
        marqueeFrom.x,
        marqueeFrom.y,
        point.x,
        point.y,
        bitmap.width,
        bitmap.height,
      );
      if (ratio !== 'free') next = applyRatio(next, ratio, bitmap.width, bitmap.height);
      selection = next;
      return;
    }
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
    marqueeDrag = false;
    marqueeFrom = null;
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
    const nextWidth = Math.max(1, Math.min(1024, Math.round(width)));
    const nextHeight = Math.max(1, Math.min(1024, Math.round(height)));
    if (nextWidth === bitmap.width && nextHeight === bitmap.height) return;
    history.push(bitmap);
    bitmap = resize(bitmap, nextWidth, nextHeight);
    selection = null;
    dirty = true;
    fitToView();
  }

  /** Applies the marquee: the kept pixels are kept, nothing is resampled. */
  function applyCrop() {
    if (!selection) return;
    history.push(bitmap);
    bitmap = crop(bitmap, selection.x, selection.y, selection.width, selection.height);
    selection = null;
    ratio = 'free';
    dirty = true;
    fitToView();
  }

  function cancelCrop() {
    selection = null;
  }

  function lockRatio(next: Ratio) {
    ratio = next;
    if (selection) selection = applyRatio(selection, next, bitmap.width, bitmap.height);
  }

  function moveSelection(axis: 'x' | 'y', raw: number) {
    if (!selection || !Number.isFinite(raw)) return;
    const limit =
      axis === 'x' ? bitmap.width - selection.width : bitmap.height - selection.height;
    selection = { ...selection, [axis]: Math.max(0, Math.min(limit, Math.round(raw))) };
  }

  function shapeSelection(axis: 'width' | 'height', raw: number) {
    if (!selection || !Number.isFinite(raw)) return;
    selection = applyRatio(
      { ...selection, [axis]: Math.max(1, Math.round(raw)) },
      ratio,
      bitmap.width,
      bitmap.height,
    );
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
    const channel = session.channel;
    if (!active || !dirty || saving) return;
    const targetPack = pack;
    const targetPath = path;
    if (
      loadState !== 'ready' ||
      !sourceSnapshot ||
      loadedPack !== targetPack ||
      loadedPath !== targetPath
    ) {
      error = 'This texture has no verified source. Reopen it before applying pixels.';
      return;
    }
    if (!connection.connected || !channel) {
      error = 'Reconnect before applying this texture.';
      return;
    }
    saving = true;
    try {
      const bytes = await encode();
      let currentSnapshot: AssetSnapshot;
      try {
        currentSnapshot = await readSnapshot(channel, targetPack, targetPath);
      } catch (e) {
        error = `Could not verify the texture before writing; no upload was attempted and your pixels are preserved. ${errorMessage(e)}`;
        return;
      }
      if (!sameAssetSnapshot(sourceSnapshot, currentSnapshot)) {
        error = 'This texture changed on the server since it was opened. No upload was attempted; your pixels are preserved. Reopen the texture to inspect the current file.';
        return;
      }
      if (!connection.connected || session.channel !== channel) {
        error = 'The connection changed during verification; no upload was attempted and your pixels are preserved.';
        return;
      }
      if (pack !== targetPack || path !== targetPath) {
        error = 'The texture asset context changed during verification; no upload was attempted and your pixels are preserved.';
        return;
      }
      unwrap(await assetOps.put(channel, targetPack, targetPath, bytes));
      sourceSnapshot = { kind: 'present', data: base64FromBytes(bytes) };
      loadedPack = targetPack;
      loadedPath = targetPath;
      remember(hex);
      dirty = false;
      missing = false;
      logActivity('save', `${targetPath} saved; targeted reload queued`);
      await onSaved?.();
    } catch (e) {
      error = `Texture apply failed; your pixels are preserved. ${errorMessage(e)}`;
    } finally {
      saving = false;
    }
  }

  function base64FromBytes(bytes: Uint8Array): string {
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    return btoa(binary);
  }

  function remember(value: string) {
    recents = [value, ...recents.filter((entry) => entry !== value)].slice(0, 8);
  }

  async function importPng(event: Event) {
    if (!active || !canEdit) return;
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
    if (!canEdit) return;
    const blob = new Blob([await encode()], { type: 'image/png' });
    const url = URL.createObjectURL(blob);
    const anchorElement = document.createElement('a');
    anchorElement.href = url;
    anchorElement.download = name;
    anchorElement.click();
    URL.revokeObjectURL(url);
  }

  function onResize() {
    if (active && canEdit) fitToView();
  }

  function close() {
    if (saving) {
      error = 'Wait for this texture save to finish before closing.';
      return;
    }
    if (dirty && !confirm('Close without saving this texture?')) return;
    onClose();
  }

  function onKeydown(event: KeyboardEvent) {
    if (!active) return;
    if (!canEdit) {
      if (event.key === 'Escape') close();
      return;
    }
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
    if (event.key === 'Escape') {
      // Esc cancels an active marquee first; only a bare Escape closes.
      if (selection) {
        cancelCrop();
        return;
      }
      close();
      return;
    }
    if (event.key === 'Enter' && selection) {
      applyCrop();
      return;
    }
    const match = TOOLS.find((entry) => entry.key.toLowerCase() === event.key.toLowerCase());
    if (match) tool = match.id;
  }
</script>

<svelte:window onkeydown={onKeydown} onresize={onResize} />

<div class="texture-editor">
  <header>
    <span class="title" title={path}>{name}</span>
    <span class="dims">{bitmap.width}×{bitmap.height}</span>
    {#if loadState === 'loading'}<span class="loading">loading…</span>{/if}
    {#if missing}<span class="new">new</span>{/if}
    <span class="spacer"></span>
    <button type="button" onclick={undo} disabled={!canEdit || !history.canUndo} title="undo (Ctrl+Z)">↶</button>
    <button type="button" onclick={redo} disabled={!canEdit || !history.canRedo} title="redo (Ctrl+Shift+Z)">
      ↷
    </button>
    <label class="ghost" title="replace with a PNG">
      Import
      <input type="file" accept="image/png" onchange={importPng} disabled={!active || !canEdit} hidden />
    </label>
    <button type="button" class="btn" onclick={exportPng} disabled={!canEdit}>Export</button>
    <button type="button" class="btn" onclick={close}>Close</button>
    <button type="button" class="btn btn-primary" onclick={save} disabled={!active || !canEdit || !dirty || saving}>
      {saving ? 'Saving…' : 'Save & apply'}
    </button>
  </header>

  {#if error || draftError}<p class="error">{error || draftError}</p>{/if}
  <p class="source-note">
    Save checks the current bytes first. The asset API cannot make that check atomic with the upload.
  </p>

  <div class="work" inert={!canEdit}>
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

    <aside class="settings">
      {#if toolNeedsColor}
        <input type="color" bind:value={hex} onchange={() => remember(hex)} title="colour" />
        <input type="text" class="hex" bind:value={hex} spellcheck="false" />
        <label class="range" title="alpha">
          α
          <input type="range" min="0" max="255" bind:value={alpha} />
        </label>
      {:else if tool === 'pick'}
        <span class="group">Picked</span>
        <div class="picked">
          <span class="swatch" style="background:{hex}"></span>
          <span class="mono">{hex}</span>
          <span class="mono faint">α {alpha}</span>
        </div>
      {/if}

      {#if tool === 'crop'}
        <span class="group">Crop selection</span>
        {#if selection}
          <div class="nums">
            <label>x <input
                class="num"
                type="number"
                min="0"
                value={selection.x}
                onchange={(e) => moveSelection('x', (e.currentTarget as HTMLInputElement).valueAsNumber)}
              /></label>
            <label>y <input
                class="num"
                type="number"
                min="0"
                value={selection.y}
                onchange={(e) => moveSelection('y', (e.currentTarget as HTMLInputElement).valueAsNumber)}
              /></label>
            <label>w <input
                class="num"
                type="number"
                min="1"
                value={selection.width}
                onchange={(e) =>
                  shapeSelection('width', (e.currentTarget as HTMLInputElement).valueAsNumber)}
              /></label>
            <label>h <input
                class="num"
                type="number"
                min="1"
                value={selection.height}
                onchange={(e) =>
                  shapeSelection('height', (e.currentTarget as HTMLInputElement).valueAsNumber)}
              /></label>
          </div>
          <div class="chips">
            {#each RATIOS as option (option)}
              <button
                type="button"
                class:active={ratio === option}
                onclick={() => lockRatio(option)}
              >
                {option}
              </button>
            {/each}
          </div>
          <div class="actions">
            <button type="button" class="apply" onclick={applyCrop} title="apply (Enter)">
              Crop
            </button>
            <button type="button" class="btn" onclick={cancelCrop} title="cancel (Esc)">
              Cancel
            </button>
          </div>
        {:else}
          <p class="hint">Drag a region on the canvas.</p>
        {/if}
      {:else if PAINT_TOOLS.includes(tool)}
        <label class="range" title="brush size">
          ⬛
          <input type="range" min="1" max="8" bind:value={size} />
        </label>
        <div class="chips">
          <button type="button" class:active={mirror} title="mirror across the vertical axis" onclick={() => (mirror = !mirror)}>
            ⇔ mirror
          </button>
          {#if tool === 'rect'}
            <button type="button" class:active={filled} title="filled" onclick={() => (filled = !filled)}>
              ■ filled
            </button>
          {/if}
        </div>
      {/if}

      {#if toolNeedsColor && recents.length > 0}
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

      {#if toolNeedsColor && palette.length > 0}
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

      <span class="group">Canvas</span>
      <div class="dims-input">
        <input
          class="num"
          type="number"
          min="1"
          max="1024"
          bind:value={canvasWidth}
          title="width in pixels"
        />
        <span class="faint">×</span>
        <input
          class="num"
          type="number"
          min="1"
          max="1024"
          bind:value={canvasHeight}
          title="height in pixels"
        />
      </div>
      <button
        type="button"
        class="apply"
        disabled={canvasWidth === bitmap.width && canvasHeight === bitmap.height}
        onclick={() => setSize(canvasWidth, canvasHeight)}
        title="resample to the entered size (nearest neighbour)"
      >
        Scale
      </button>
      <div class="chips">
        {#each [16, 32, 64] as preset (preset)}
          <button
            type="button"
            class:active={bitmap.width === preset && bitmap.height === preset}
            onclick={() => setSize(preset, preset)}
          >
            {preset}²
          </button>
        {/each}
      </div>
    </aside>
  </div>
</div>

<style>
  .texture-editor {
    /* Flex, not grid tracks: the error line is optional, and positional rows
       would drop the canvas into an `auto` track and collapse it. */
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    background: var(--bg-app);
  }
  header {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .title {
    font-size: var(--text-base);
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .dims,
  .new {
    font: var(--text-xs) var(--mono);
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
  header button:not(.btn),
  header .ghost {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  header button:not(.btn):disabled {
    color: var(--faint);
    border-color: var(--border);
    cursor: default;
  }
  .work {
    display: grid;
    flex: 1 1 0;
    min-height: 0;
    grid-template-columns: 36px minmax(0, 1fr) minmax(0, 168px);
    min-width: 0;
    overflow: hidden;
  }
  .tools {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: var(--space-2) var(--space-1);
    border-right: 1px solid var(--border);
  }
  .tools button:not(.btn) {
    height: 28px;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--radius);
    color: var(--muted);
    font-size: var(--text-md);
    cursor: pointer;
  }
  .tools button:not(.btn).active {
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
  .settings {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-2);
    border-left: 1px solid var(--border);
    overflow-y: auto;
    overflow-x: hidden;
    min-width: 0;
  }
  .settings input[type='color'] {
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
    font: var(--text-sm) var(--mono);
    padding: var(--space-1) var(--space-2);
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
  .range {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-xs);
    color: var(--faint);
    min-width: 0;
  }
  .range input {
    flex: 1;
    min-width: 0;
  }
  .group {
    font-size: var(--text-xs);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
    margin-top: var(--space-1);
  }
  .swatches {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(20px, 1fr));
    gap: var(--space-1);
  }
  .swatches button:not(.btn) {
    aspect-ratio: 1;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    cursor: pointer;
    padding: 0;
  }
  .nums {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--space-1) var(--space-2);
  }
  .nums label,
  .dims-input {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    font: var(--text-xs) var(--mono);
    color: var(--faint);
    min-width: 0;
  }
  .num {
    width: 100%;
    min-width: 0;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: inherit;
    font: var(--text-xs) var(--mono);
    padding: var(--space-1) var(--space-1);
    box-sizing: border-box;
  }
  .dims-input {
    justify-content: space-between;
  }
  .dims-input .num {
    flex: 1;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .chips button:not(.btn) {
    flex: 1;
    min-width: 0;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    font-size: var(--text-xs);
    padding: var(--space-1) var(--space-1);
    cursor: pointer;
    white-space: nowrap;
  }
  .chips button:not(.btn).active {
    color: var(--fg);
    background: var(--bg-raised);
    border-color: var(--accent);
  }
  .actions {
    display: flex;
    gap: var(--space-1);
  }
  .actions .apply,
  .apply {
    flex: 1;
    background: var(--accent);
    border: 1px solid transparent;
    border-radius: var(--radius);
    color: var(--accent-ink);
    font-size: var(--text-sm);
    padding: var(--space-1) 0;
    cursor: pointer;
  }
  .apply:disabled {
    background: none;
    border-color: var(--border);
    color: var(--faint);
    cursor: default;
  }
  .picked {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-xs);
    min-width: 0;
  }
  .picked .swatch {
    width: 18px;
    height: 18px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-strong);
    flex: none;
  }
  .mono {
    font-family: var(--mono);
  }
  .faint {
    color: var(--faint);
  }
  .hint {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--faint);
  }
  .error {
    margin: 0;
    flex: none;
    padding: var(--space-2) var(--space-3);
    background: var(--error-bg);
    color: var(--error);
    font-size: var(--text-sm);
  }
  .loading {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .source-note {
    margin: 0;
    padding: var(--space-1) var(--space-3);
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
