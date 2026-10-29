/**
 * The GUI studio's reactive core: one object that owns the document history,
 * the selection, the camera, the pixel assets and the save, so the canvas, the
 * layers panel, the palette and the inspector are views over the same state.
 * The rules live in the pure modules next to it (commands, scene, snap, ...);
 * this class only wires them to Svelte runes.
 */
import { assetOps, ops as authoringOps, unwrap, type RpcChannel } from '../api';
import { reconcileDocumentDraft, type DocumentDraft } from '../drafts.svelte';
import { decodeImage, encodePng, readImageFile } from './browser-image';
import { canvasSize } from './chest';
import { composeCanvas } from './compose';
import { addLayer, compose, patchLayers, setDocumentFields, type Command } from './commands';
import { planCustomize, type CustomizeEnvironment } from './customize';
import { GuiHistory } from './history';
import { analyzeItems } from './items';
import {
  findLayer,
  isLocked,
  layerLabel,
  parseGui,
  starterDocument,
  type GuiDocument,
  type GuiLayer,
  type GuiLocation,
} from './model';
import * as ops from './ops';
import {
  ERASER,
  Stroke,
  clampBrush,
  diffAll,
  fillPatch,
  pushRecent,
  resizedCanvas,
  type BrushSettings,
} from './paint';
import { PixelCommand } from './pixel-command';
import { ArgbCanvas } from './raster/canvas';
import type { Argb } from './raster/color';
import { bareType } from './raster/flatten';
import { componentOf, newLayer, type GuiComponentInfo, type GuiRegistry } from './registry';
import {
  base64Of,
  isEmptyPlan,
  paintReference,
  planSave,
  sendSave,
  textureAssetPath,
  uploadReference,
} from './save';
import type { Guide } from './snap';
import { compiledOrder, layerBounds, orderBadges, overlays, sceneOf, type Scene } from './scene';
import { analyzeText } from './text';
import { centered, fitView, panBy, stepZoom, zoomTo, type Point, type Size, type View } from './viewport';

export type PaintTool = 'brush' | 'eraser' | 'fill' | 'picker';
export type Tool = 'select' | 'hand' | PaintTool;

export function isPaintTool(tool: Tool): tool is PaintTool {
  return tool === 'brush' || tool === 'eraser' || tool === 'fill' || tool === 'picker';
}

const PAINT_STORAGE_KEY = 'materione.gui.paint';
const DEFAULT_PAINT_COLOR: Argb = 0xff3c8527;

export class GuiStudioState {
  readonly #history = new GuiHistory(starterDocument());
  #location: GuiLocation | null = null;
  readonly #images = new Map<string, ArgbCanvas | null>();
  readonly #loading = new Set<string>();
  /** Pixel assets made or edited in this session, by texture reference; sent on save while referenced. */
  readonly #dirtyAssets = new Map<string, ArgbCanvas>();
  /** The original bytes of uploaded images (base64), sent as they were chosen rather than re-encoded. */
  readonly #uploads = new Map<string, string>();
  #stroke: Stroke | null = null;
  #lastStrokeEnd = new Map<string, Point>();
  #redraw = 0;
  #hintTimer: ReturnType<typeof setTimeout> | undefined;

  doc = $state.raw<GuiDocument>(starterDocument());
  /** What the canvas shows while a drag is in flight (not yet in the history). */
  preview = $state.raw<GuiDocument | null>(null);
  loaded = $state(false);
  dirty = $state(false);
  canUndo = $state(false);
  canRedo = $state(false);
  undoLabel = $state<string | null>(null);
  redoLabel = $state<string | null>(null);

  selection = $state<string[]>([]);
  /** The layer whose name is being edited in the layers panel. */
  renaming = $state<string | null>(null);
  tool = $state<Tool>('select');
  /** The brush (size 1-8, square or round); the eraser uses the same footprint. */
  brush = $state<BrushSettings>({ size: 1, shape: 'square' });
  /** The paint colour (straight ARGB) and the colours recently painted or picked, newest first. */
  color = $state<Argb>(DEFAULT_PAINT_COLOR);
  recent = $state<Argb[]>([]);
  /** A one-line message for the status bar (why a tool did nothing), cleared after a few seconds. */
  hint = $state('');
  /** The text layer being edited in place on the canvas, and whether the edit is live. */
  editingText = $state<string | null>(null);
  /** The "add an image" dialog (upload or pick from the Assets workspace) is open. */
  choosing = $state(false);
  view = $state<View>({ zoom: 3, panX: 0, panY: 0 });
  /** The view is still the fitted one (no manual zoom or pan): a window resize refits it. */
  autoFit = $state(true);
  stage = $state<Size>({ w: 0, h: 0 });
  showSlots = $state(false);
  showPixelGrid = $state(true);
  snapping = $state(true);
  /** The GUI pixel under the pointer, for the status bar. */
  cursor = $state<Point | null>(null);
  /** Space is held: drag pans, whatever the tool. */
  panKey = $state(false);
  /** Snap guides to draw while a drag is in flight. */
  guides = $state.raw<readonly Guide[]>([]);
  /** The slot an item would land in while it is dragged. */
  dropSlot = $state<number | null>(null);

  registry = $state.raw<GuiRegistry | null>(null);
  registryError = $state('');
  /** Why the file is read-only here (a layer the model cannot hold faithfully). */
  blocking = $state<string[]>([]);
  issues = $state<string[]>([]);
  imageVersion = $state(0);
  saving = $state(false);
  saveError = $state('');
  conflict = $state(false);
  notice = $state('');

  constructor(
    readonly pack: string,
    readonly path: string,
    readonly rootId: string,
  ) {
    this.#restorePaintSettings();
  }

  /** The document the canvas draws: the live drag preview, else the committed one. */
  shown = $derived(this.preview ?? this.doc);
  readOnly = $derived(this.blocking.length > 0);
  size = $derived(canvasSize(this.doc.rows));
  scene = $derived.by<Scene>(() => {
    void this.imageVersion;
    return sceneOf(this.shown, this.registry, (reference) => this.imageFor(reference));
  });
  badges = $derived(orderBadges(this.shown, this.scene));
  order = $derived(compiledOrder(this.shown, this.scene));
  marks = $derived(overlays(this.shown, this.scene));
  texts = $derived(this.marks.filter((mark) => mark.target === 'text'));
  items = $derived(this.marks.filter((mark) => mark.target === 'slot'));
  /** What the compiler will do with each text and item layer (gui.md section 4): skipped runs, with why. */
  textIssues = $derived(analyzeText(this.shown));
  itemIssues = $derived(analyzeItems(this.shown));
  /** The canvas as the player sees the background and smallcaps title: the eyedropper samples this. */
  composite = $derived.by<ArgbCanvas>(() => {
    void this.imageVersion;
    return composeCanvas(this.shown, {
      width: this.size.width,
      height: this.size.height,
      rows: this.shown.rows,
      image: (reference) => this.imageFor(reference),
    });
  });
  selected = $derived(this.shown.layers.filter((layer) => this.selection.includes(layer.id)));

  // ------------------------------------------------------------------ loading

  /** The revision the document in the studio was read at (or saved as). */
  seenRev = -1;

  /** Parses the file the API returned; false when it is not a GUI document at all. */
  load(parsed: unknown, rootId: string, rev = -1): boolean {
    const result = parseGui(parsed, rootId);
    if (!result) return false;
    this.#location = result.location;
    this.seenRev = rev;
    this.#history.reset(result.doc);
    this.blocking = result.blocking;
    this.issues = result.issues;
    this.selection = [];
    this.preview = null;
    this.#stroke = null;
    this.editingText = null;
    this.choosing = false;
    // pixels edited or uploaded here and never saved are discarded; the next read gets the server's
    for (const reference of this.#dirtyAssets.keys()) this.#images.delete(reference);
    this.#dirtyAssets.clear();
    this.#uploads.clear();
    this.#lastStrokeEnd.clear();
    this.imageVersion++;
    this.saveError = '';
    this.conflict = false;
    this.loaded = true;
    this.sync();
    this.fit(); // the canvas may have been measured before the document told it how tall it is
    return true;
  }

  setRegistry(registry: GuiRegistry | null, error = ''): void {
    this.registry = registry;
    this.registryError = error;
  }

  /** Mirrors the history into reactive fields. */
  sync(): void {
    this.doc = this.#history.doc;
    this.dirty = this.#history.dirty;
    this.canUndo = this.#history.canUndo;
    this.canRedo = this.#history.canRedo;
    this.undoLabel = this.#history.undoLabel;
    this.redoLabel = this.#history.redoLabel;
    const ids = new Set(this.#history.doc.layers.map((layer) => layer.id));
    if (this.selection.some((id) => !ids.has(id))) this.selection = this.selection.filter((id) => ids.has(id));
  }

  // ------------------------------------------------------------------ history

  run(command: Command | null): boolean {
    if (!command || this.readOnly) return false;
    this.#history.execute(command);
    this.sync();
    return true;
  }

  undo(): void {
    if (this.readOnly) return;
    this.#history.undo();
    this.sync();
  }

  redo(): void {
    if (this.readOnly) return;
    this.#history.redo();
    this.sync();
  }

  // ------------------------------------------------------------------ selection

  select(ids: readonly string[]): void {
    this.selection = [...ids];
  }

  toggle(id: string): void {
    this.selection = this.selection.includes(id)
      ? this.selection.filter((existing) => existing !== id)
      : [...this.selection, id];
  }

  selectAll(): void {
    this.selection = this.doc.layers.filter((layer) => !isLocked(layer)).map((layer) => layer.id);
  }

  // ------------------------------------------------------------------ edits

  addComponent(info: GuiComponentInfo): void {
    if (this.readOnly) return;
    const { layer } = ops.addComponent(this.doc, this.scene, info);
    let made = layer;
    if (bareType(layer) === 'paint') {
      // A paint layer owns a canvas-sized PNG; it exists from the first moment, blank, and is written on save.
      const reference = paintReference(this.path, layer.id);
      made = { ...layer, image: reference } as GuiLayer;
      const blank = new ArgbCanvas(this.size.width, this.size.height);
      this.#images.set(reference, blank);
      this.#dirtyAssets.set(reference, blank);
      this.imageVersion++;
    }
    if (bareType(layer) === 'item') made = { ...layer, slot: this.freeSlot() } as GuiLayer;
    this.run(addLayer(this.doc, made, this.doc.layers.length, `Add ${info.displayName}`));
    this.selection = [made.id];
    // a new text layer starts in edit mode on the canvas, like a text tool
    if (bareType(made) === 'text') this.editingText = made.id;
  }

  deleteSelection(): void {
    this.run(ops.deleteLayers(this.doc, this.selection));
  }

  duplicateSelection(): void {
    const { command, created } = ops.duplicateLayers(this.doc, this.scene, this.selection);
    if (this.run(command)) this.selection = created;
  }

  nudgeSelection(dx: number, dy: number): void {
    this.run(ops.nudge(this.doc, this.scene, this.selection, dx, dy));
  }

  setField(id: string, field: string, value: unknown, coalesce?: string): void {
    this.run(ops.setField(this.doc, id, field, value, 'Edit layer', coalesce ?? `field:${id}:${field}`));
  }

  setFieldOnSelection(field: string, value: unknown): void {
    this.run(ops.setFieldOn(this.doc, this.selection, field, value, 'Edit layers', `field:*:${field}`));
  }

  setTitle(title: string): void {
    this.run(setDocumentFields(this.doc, { title }, 'Edit title', 'title'));
  }

  /** Changing rows resizes the canvas; a frame panel that spans the whole canvas follows it. */
  setRows(rows: number): void {
    const next = Math.min(6, Math.max(1, Math.round(rows)));
    if (next === this.doc.rows) return;
    const from = canvasSize(this.doc.rows);
    const to = canvasSize(next);
    const frames: Record<string, { height: number }> = {};
    for (const layer of this.doc.layers) {
      if (bareType(layer) === 'panel' && layer.x === 0 && layer.y === 0 && layer.width === from.width && layer.height === from.height) {
        frames[layer.id] = { height: to.height };
      }
    }
    this.run(compose('Change rows', [
      setDocumentFields(this.doc, { rows: next }, 'Change rows'),
      patchLayers(this.doc, frames, 'Resize frame'),
    ]));
    this.fit();
  }

  // ------------------------------------------------------------------ camera

  fit(): void {
    if (this.stage.w > 0) {
      this.view = fitView(this.stage, { w: this.size.width, h: this.size.height });
      this.autoFit = true;
    }
  }

  actualSize(): void {
    if (this.stage.w > 0) {
      this.view = centered(this.stage, { w: this.size.width, h: this.size.height }, 1);
      this.autoFit = false;
    }
  }

  /** Any manual camera change: the view is the author's now, not the fitted one. */
  setView(view: View): void {
    this.view = view;
    this.autoFit = false;
  }

  zoomStep(direction: 1 | -1, anchor?: Point): void {
    this.setView(stepZoom(this.view, direction, anchor ?? { x: this.stage.w / 2, y: this.stage.h / 2 }));
  }

  zoomTo(zoom: number, anchor?: Point): void {
    this.setView(zoomTo(this.view, zoom, anchor ?? { x: this.stage.w / 2, y: this.stage.h / 2 }));
  }

  pan(dx: number, dy: number): void {
    this.setView(panBy(this.view, dx, dy, this.stage, { w: this.size.width, h: this.size.height }));
  }

  // ------------------------------------------------------------------ pixel assets

  /** The decoded texture behind a reference, or null while it loads or when it does not exist. */
  imageFor(reference: string): ArgbCanvas | null {
    void this.imageVersion;
    return this.#images.get(reference) ?? null;
  }

  hasImage(reference: string): boolean {
    return this.#images.get(reference) !== undefined && this.#images.get(reference) !== null;
  }

  /** Starts loading every texture the layers name that is not yet known. */
  async ensureImages(channel: RpcChannel | null): Promise<void> {
    if (!channel) return;
    const wanted = new Set(
      this.doc.layers.flatMap((layer) => (typeof layer.image === 'string' && layer.image ? [layer.image] : [])),
    );
    await Promise.all([...wanted].map(async (reference) => {
      if (this.#images.has(reference) || this.#loading.has(reference)) return;
      this.#loading.add(reference);
      try {
        const payload = unwrap(await assetOps.get(channel, this.pack, textureAssetPath(reference)));
        this.#images.set(reference, await decodeImage(String(payload.data ?? '')));
      } catch {
        // A missing or unreadable texture is a placeholder in the canvas, never a failure of the studio.
        this.#images.set(reference, null);
      } finally {
        this.#loading.delete(reference);
        this.imageVersion++;
      }
    }));
  }

  /** Drops a cached texture so the next `ensureImages` re-reads it (after the pack changed). */
  forgetImage(reference: string): void {
    this.#images.delete(reference);
  }

  // ------------------------------------------------------------------ paint tools

  #restorePaintSettings(): void {
    try {
      const raw = globalThis.localStorage?.getItem(PAINT_STORAGE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw) as { color?: unknown; recent?: unknown; size?: unknown; shape?: unknown };
      if (typeof saved.color === 'number') this.color = saved.color >>> 0;
      if (Array.isArray(saved.recent)) {
        this.recent = saved.recent.filter((entry): entry is number => typeof entry === 'number').map((n) => n >>> 0).slice(0, 12);
      }
      if (typeof saved.size === 'number') {
        this.brush = { size: clampBrush(saved.size), shape: saved.shape === 'round' ? 'round' : 'square' };
      }
    } catch {
      // storage is a convenience: private windows and blocked storage just start from the defaults
    }
  }

  #savePaintSettings(): void {
    try {
      globalThis.localStorage?.setItem(
        PAINT_STORAGE_KEY,
        JSON.stringify({ color: this.color, recent: this.recent, size: this.brush.size, shape: this.brush.shape }),
      );
    } catch {
      // see #restorePaintSettings
    }
  }

  /** Sets the paint colour; `remember` also puts it first in the recent colours. */
  setColor(color: Argb, remember = false): void {
    this.color = color >>> 0;
    if (remember) this.recent = pushRecent(this.recent, this.color);
    this.#savePaintSettings();
  }

  setBrush(patch: Partial<BrushSettings>): void {
    this.brush = {
      size: patch.size === undefined ? this.brush.size : clampBrush(patch.size),
      shape: patch.shape ?? this.brush.shape,
    };
    this.#savePaintSettings();
  }

  /** A short message for the status bar. */
  say(message: string, ms = 3600): void {
    this.hint = message;
    clearTimeout(this.#hintTimer);
    this.#hintTimer = setTimeout(() => (this.hint = ''), ms);
  }

  /** The layer a paint tool would draw on right now, without creating anything. */
  paintLayerNow(): GuiLayer | null {
    const selected = this.selected.length === 1 ? this.selected[0]! : null;
    if (selected && bareType(selected) === 'paint') return selected;
    return [...this.doc.layers].reverse().find((layer) => bareType(layer) === 'paint' && layer.visible !== false && !isLocked(layer)) ?? null;
  }

  /** Selecting a paint tool: it needs a paint layer, and the author is told which one it will use. */
  chooseTool(tool: Tool): void {
    this.tool = tool;
    if (tool === 'brush' || tool === 'eraser' || tool === 'fill') this.#previousPaintTool = tool;
    if (!isPaintTool(tool)) return;
    const layer = this.paintLayerNow();
    if (tool === 'picker') this.say('Click the canvas to pick a colour.');
    else if (layer) this.say(`Painting on "${layerLabel(layer)}".`);
    else this.say('Click the canvas to start a Paint layer.');
  }

  #paintTarget(): { layer: GuiLayer; reference: string; canvas: ArgbCanvas } | null {
    if (this.readOnly) {
      this.say('This file is read-only here.');
      return null;
    }
    let layer = this.paintLayerNow();
    if (!layer) {
      const info = this.registry?.components.find((component) => component.id.endsWith(':paint') || component.id === 'paint');
      if (!info) {
        this.say('This server does not offer a Paint layer.');
        return null;
      }
      this.addComponent(info);
      layer = this.doc.layers[this.doc.layers.length - 1] ?? null;
      if (!layer) return null;
    }
    if (isLocked(layer)) {
      this.say(`"${layerLabel(layer)}" is locked: unlock it to paint.`);
      return null;
    }
    if (layer.visible === false) {
      this.say(`"${layerLabel(layer)}" is hidden: show it to paint.`);
      return null;
    }
    if (this.selection.length !== 1 || this.selection[0] !== layer.id) this.selection = [layer.id];
    const reference = typeof layer.image === 'string' && layer.image ? layer.image : paintReference(this.path, layer.id);
    if (layer.image !== reference) this.run(ops.setField(this.doc, layer.id, 'image', reference, 'Set paint texture'));
    let canvas = this.#images.get(reference);
    if (canvas === undefined) {
      this.say('The layer is still loading.');
      return null;
    }
    const { width, height } = this.size;
    if (canvas === null || canvas.width !== width || canvas.height !== height) {
      // no file yet, or the canvas grew with the rows: a transparent canvas of the right size, overlap kept
      canvas = canvas ? resizedCanvas(canvas, width, height) : new ArgbCanvas(width, height);
      this.#images.set(reference, canvas);
      this.#dirtyAssets.set(reference, canvas);
    }
    return { layer, reference, canvas };
  }

  /** Redraws at most once per frame while a stroke is in flight. */
  #kick(): void {
    if (this.#redraw) return;
    const schedule = globalThis.requestAnimationFrame ?? ((callback: () => void) => setTimeout(callback, 16) as unknown as number);
    this.#redraw = schedule(() => {
      this.#redraw = 0;
      this.imageVersion++;
    });
  }

  #touch(reference: string): void {
    const canvas = this.#images.get(reference);
    if (canvas) this.#dirtyAssets.set(reference, canvas);
    this.imageVersion++;
  }

  #pixelCommand(label: string, patch: NonNullable<ReturnType<Stroke['end']>>): Command {
    return new PixelCommand(label, patch, (reference) => this.#images.get(reference) ?? null, (reference) => this.#touch(reference));
  }

  /** Begins a brush or eraser stroke at a GUI pixel; shift joins the previous stroke's end with a straight line. */
  beginStroke(point: Point, erase: boolean, straight: boolean): boolean {
    const target = this.#paintTarget();
    if (!target) return false;
    const from = straight ? this.#lastStrokeEnd.get(target.reference) : undefined;
    this.#stroke = new Stroke(target.canvas, target.reference, erase ? ERASER : this.color, this.brush, point, from);
    this.#strokeErases = erase;
    this.#kick();
    return true;
  }

  #strokeErases = false;

  get stroking(): boolean {
    return this.#stroke !== null;
  }

  /** Joins the pointer's new positions (coalesced events included) to the stroke. */
  strokeTo(points: readonly Point[]): void {
    const stroke = this.#stroke;
    if (!stroke) return;
    let changed = false;
    for (const point of points) changed = stroke.to(point) || changed;
    if (changed) this.#kick();
  }

  /** Ends the stroke: ONE command, however many events drew it (nothing when it changed no pixel). */
  endStroke(): void {
    const stroke = this.#stroke;
    this.#stroke = null;
    if (!stroke) return;
    this.#lastStrokeEnd.set(stroke.reference, stroke.last);
    const patch = stroke.end();
    if (!patch) {
      this.imageVersion++;
      return;
    }
    this.run(this.#pixelCommand(this.#strokeErases ? 'Erase' : 'Paint stroke', patch));
    if (!this.#strokeErases) this.setColor(this.color, true);
  }

  /** Flood fill (contiguous, exact colour match) with the paint colour. */
  fillAt(point: Point): void {
    const target = this.#paintTarget();
    if (!target) return;
    const patch = fillPatch(target.canvas, target.reference, point.x, point.y, this.color);
    if (!patch) {
      this.say('Nothing to fill there.');
      return;
    }
    this.run(this.#pixelCommand('Fill', patch));
    this.setColor(this.color, true);
  }

  /** Empties the paint layer (one undoable step). */
  clearPaint(id: string): void {
    const layer = findLayer(this.doc, id);
    const reference = layer && typeof layer.image === 'string' ? layer.image : '';
    const canvas = this.#images.get(reference);
    if (!layer || !canvas || this.readOnly || isLocked(layer)) return;
    const snapshot = canvas.pixels.slice();
    canvas.pixels.fill(0);
    const patch = diffAll(reference, snapshot, canvas);
    if (patch) this.run(this.#pixelCommand('Clear paint layer', patch));
    else this.say('The layer is already empty.');
  }

  #previousPaintTool: PaintTool = 'brush';

  /** The eyedropper: the colour the player sees at a GUI pixel (the composited canvas, text included). */
  pickAt(point: Point): boolean {
    const color = this.composite.get(point.x, point.y);
    if (color >>> 24 === 0) {
      this.say('That pixel is transparent: nothing to pick.');
      return false;
    }
    this.setColor(color);
    return true;
  }

  /** The pick is over: the colour joins the recent ones, and the picker hands back to the last paint tool. */
  finishPick(): void {
    this.setColor(this.color, true);
    if (this.tool === 'picker') this.tool = this.#previousPaintTool;
  }

  // ------------------------------------------------------------------ image layers

  /** Every texture reference known to the studio or the pack, for naming new uploads. */
  #takenReferences(packTextures: readonly string[]): Set<string> {
    return new Set([...packTextures, ...this.#images.keys(), ...this.doc.layers.flatMap((l) => (typeof l.image === 'string' ? [l.image] : []))]);
  }

  /** The natural size of an image layer's texture, if it is loaded. */
  naturalSize(layer: GuiLayer): { w: number; h: number } | null {
    const image = typeof layer.image === 'string' ? this.imageFor(layer.image) : null;
    return image ? { w: image.width, h: image.height } : null;
  }

  /** Reads an uploaded file into the studio as a new texture under the GUI's folder; null when it cannot be used. */
  async #ingest(file: File, packTextures: readonly string[]): Promise<{ reference: string; canvas: ArgbCanvas } | null> {
    try {
      const read = await readImageFile(file);
      const reference = uploadReference(this.path, file.name, this.#takenReferences(packTextures));
      this.#images.set(reference, read.canvas);
      this.#dirtyAssets.set(reference, read.canvas);
      this.#uploads.set(reference, base64Of(read.png));
      this.imageVersion++;
      return { reference, canvas: read.canvas };
    } catch (error) {
      this.say(error instanceof Error ? error.message : 'That file is not an image the studio can read.');
      return null;
    }
  }

  /** A new image layer from a file: stored beside the GUI's other textures, natural size, centered (or at `at`). */
  async addImageFromFile(file: File, packTextures: readonly string[], at?: Point): Promise<boolean> {
    if (this.readOnly) return false;
    const info = this.registry?.components.find((component) => component.id.endsWith(':image') || component.id === 'image');
    const ingested = await this.#ingest(file, packTextures);
    if (!info || !ingested) {
      if (!info) this.say('This server does not offer an Image layer.');
      return false;
    }
    return this.#placeImage(info, ingested.reference, ingested.canvas, at);
  }

  /** A new image layer from a texture already in the pack (the Assets workspace). */
  async addImageFromAsset(channel: RpcChannel | null, reference: string): Promise<boolean> {
    if (this.readOnly) return false;
    const info = this.registry?.components.find((component) => component.id.endsWith(':image') || component.id === 'image');
    const canvas = await this.#loadTexture(channel, reference);
    if (!info || !canvas) return false;
    return this.#placeImage(info, reference, canvas);
  }

  #placeImage(info: GuiComponentInfo, reference: string, canvas: ArgbCanvas, at?: Point): boolean {
    const w = canvas.width;
    const h = canvas.height;
    const x = Math.round(at ? at.x - w / 2 : (this.size.width - w) / 2);
    const y = Math.round(at ? at.y - h / 2 : (this.size.height - h) / 2);
    const layer = newLayer(info, this.doc.layers.map((entry) => entry.id), { image: reference, x, y, width: w, height: h });
    const named = { ...layer, name: baseName(reference) } as GuiLayer;
    this.run(addLayer(this.doc, named, this.doc.layers.length, 'Add image'));
    this.selection = [named.id];
    return true;
  }

  async #loadTexture(channel: RpcChannel | null, reference: string): Promise<ArgbCanvas | null> {
    const known = this.#images.get(reference);
    if (known) return known;
    if (!channel) return null;
    try {
      const payload = unwrap(await assetOps.get(channel, this.pack, textureAssetPath(reference)));
      const canvas = await decodeImage(String(payload.data ?? ''));
      this.#images.set(reference, canvas);
      this.imageVersion++;
      return canvas;
    } catch {
      this.say('That texture could not be read.');
      return null;
    }
  }

  /** Points an image layer at another texture (from the pack), keeping its position and taking the new natural size. */
  async useTexture(channel: RpcChannel | null, id: string, reference: string): Promise<void> {
    const canvas = await this.#loadTexture(channel, reference);
    if (!canvas) return;
    this.run(ops.setFieldOn(this.doc, [id], 'image', reference, 'Change image'));
    this.resetNaturalSize(id);
  }

  async replaceImageFromFile(id: string, file: File, packTextures: readonly string[]): Promise<void> {
    const ingested = await this.#ingest(file, packTextures);
    if (!ingested) return;
    this.run(patchLayers(this.doc, {
      [id]: { image: ingested.reference, width: ingested.canvas.width, height: ingested.canvas.height },
    }, 'Replace image'));
  }

  /** The image drawn 1:1 again (its texture's own size), keeping its top-left corner. */
  resetNaturalSize(id: string): void {
    const layer = findLayer(this.doc, id);
    const natural = layer ? this.naturalSize(layer) : null;
    if (!layer || !natural) return;
    this.run(patchLayers(this.doc, { [id]: { width: natural.w, height: natural.h } }, 'Natural size', `natural:${id}`));
  }

  /** The image scaled to a whole multiple of its natural size (nearest neighbour keeps it crisp). */
  scaleImage(id: string, factor: number): void {
    const layer = findLayer(this.doc, id);
    const natural = layer ? this.naturalSize(layer) : null;
    if (!layer || !natural) return;
    this.run(patchLayers(this.doc, { [id]: { width: natural.w * factor, height: natural.h * factor } }, `Scale ${factor}x`, `scale:${id}`));
  }

  // ------------------------------------------------------------------ text layers

  beginTextEdit(id: string): void {
    const layer = findLayer(this.doc, id);
    if (!layer || this.readOnly || isLocked(layer) || layer.visible === false) return;
    this.selection = [id];
    this.editingText = id;
  }

  /** The text as typed so far, shown on the canvas without touching the history. */
  previewText(id: string, text: string): void {
    const command = ops.setField(this.doc, id, 'text', text, 'Edit text');
    this.preview = command ? command.apply(this.doc) : null;
  }

  /** Ends the edit: ONE command for the whole typing session. */
  commitText(id: string, text: string): void {
    this.preview = null;
    this.editingText = null;
    this.run(ops.setField(this.doc, id, 'text', text, 'Edit text', `text:${id}:${Date.now()}`));
  }

  cancelTextEdit(): void {
    this.preview = null;
    this.editingText = null;
  }

  // ------------------------------------------------------------------ item layers

  /** The first free container slot, for a new item layer. */
  freeSlot(): number {
    const taken = new Set(this.doc.layers.filter((layer) => bareType(layer) === 'item').map((layer) => layer.slot));
    for (let slot = 0; slot < this.doc.rows * 9; slot++) if (!taken.has(slot)) return slot;
    return 0;
  }

  /**
   * Customize: gives an item layer its own ordinary item at `items/gui/<gui-path>/<id>.yml` and
   * points the layer at it. The item's creation and the layer's new `item:` are ONE
   * `authoring.bulk` call -- the server plans every document first and writes all or none -- carrying
   * whatever other layout changes are pending, exactly as a save would. Pixel assets cannot ride a
   * bulk call, so pending ones are saved first (a normal save) and the customization follows.
   * Returns the new item's pack-relative path, or null (with `saveError` set) when nothing changed.
   */
  async customize(
    channel: RpcChannel,
    draft: DocumentDraft,
    layerId: string,
    environment: CustomizeEnvironment,
  ): Promise<string | null> {
    const layer = findLayer(this.doc, layerId);
    if (!layer || this.readOnly || this.saving || !this.#location) return null;
    const plan = planCustomize(layer, environment);
    if (!plan.ok) {
      this.saveError = plan.reason;
      return null;
    }
    this.saveError = '';
    const pendingAssets = [...this.#dirtyAssets.keys()].some((reference) =>
      this.doc.layers.some((entry) => entry.image === reference));
    if (pendingAssets && !(await this.save(channel, draft))) return null;
    this.saving = true;
    // The pointer is a normal undoable edit; if the server refuses the bulk call it is taken back.
    const edit = ops.setField(this.doc, layerId, 'item', plan.reference, 'Customize item');
    this.run(edit);
    try {
      const saved = planSave(this.#location.prefix, this.#history.baseline, this.#history.doc, new Map());
      const operations = [
        plan.operation,
        { kind: 'set' as const, path: this.path, rev: draft.rev, edits: saved.edits },
      ];
      const response = await authoringOps.bulk(channel, this.pack, operations, false);
      if (!response.ok) {
        this.conflict = response.error.code === 'MTR-0034';
        this.saveError = `${response.error.code}: ${response.error.message}`;
        this.#history.undo();
        this.sync();
        return null;
      }
      this.#history.markSaved();
      this.sync();
      this.notice = 'Created the item and linked this layer to it.';
      await reconcileDocumentDraft(draft, channel);
      this.seenRev = draft.rev;
      return plan.path;
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : String(error);
      this.#history.undo();
      this.sync();
      return null;
    } finally {
      this.saving = false;
    }
  }

  // ------------------------------------------------------------------ save

  /**
   * One authoring transaction: the changed fields and the dirty pixel assets
   * the document still refers to. On success the history's baseline moves and
   * the document draft is re-read, which is what advances the revision.
   */
  async save(channel: RpcChannel, draft: DocumentDraft): Promise<boolean> {
    if (this.readOnly || this.saving || !this.#location) return false;
    this.saving = true;
    this.saveError = '';
    this.conflict = false;
    this.notice = '';
    try {
      const encoded = new Map<string, string>();
      for (const [reference, canvas] of this.#dirtyAssets) {
        encoded.set(reference, this.#uploads.get(reference) ?? base64Of(await encodePng(canvas)));
      }
      const plan = planSave(this.#location.prefix, this.#history.baseline, this.#history.doc, encoded);
      if (isEmptyPlan(plan)) {
        this.#history.markSaved();
        this.sync();
        return true;
      }
      const response = await sendSave(channel, this.pack, this.path, draft.rev, plan);
      if (!response.ok) {
        this.conflict = response.error.code === 'MTR-0034';
        this.saveError = this.conflict
          ? 'This GUI changed on the server since you opened it. Your edits are kept here; reload to take the server version.'
          : `${response.error.code}: ${response.error.message}`;
        return false;
      }
      this.#history.markSaved();
      for (const asset of plan.assets) {
        const reference = [...encoded.keys()].find((key) => textureAssetPath(key) === asset.path);
        if (reference) {
          this.#dirtyAssets.delete(reference);
          this.#uploads.delete(reference);
        }
      }
      this.sync();
      this.notice = 'Saved. The server is applying it.';
      await reconcileDocumentDraft(draft, channel);
      this.seenRev = draft.rev; // our own write: the file moved on, the document did not
      return true;
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : String(error);
      return false;
    } finally {
      this.saving = false;
    }
  }

  /** Re-reads the document from a fresh draft, discarding the studio's edits. */
  reload(parsed: unknown, rev = -1): boolean {
    return this.load(parsed, this.rootId, rev);
  }

  boundsOf(layer: GuiLayer) {
    return layerBounds(layer, this.scene);
  }

  infoOf(layer: GuiLayer): GuiComponentInfo | undefined {
    return componentOf(this.registry, layer.type);
  }

  layerById(id: string): GuiLayer | undefined {
    return findLayer(this.shown, id);
  }
}

/** `gui/shop/main/logo` -> `logo`: the layer name an uploaded or picked texture starts with. */
function baseName(reference: string): string {
  const name = reference.slice(reference.lastIndexOf('/') + 1).replace(/_/g, ' ');
  return name ? name[0]!.toUpperCase() + name.slice(1) : 'Image';
}
