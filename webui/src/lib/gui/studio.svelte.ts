/**
 * The GUI studio's reactive core: one object that owns the document history,
 * the selection, the camera, the pixel assets and the save, so the canvas, the
 * layers panel, the palette and the inspector are views over the same state.
 * The rules live in the pure modules next to it (commands, scene, snap, ...);
 * this class only wires them to Svelte runes.
 */
import { assetOps, RpcError, unwrap, type AssetUpload, type RpcChannel } from '../api';
import { reconcileDocumentDraft, type DocumentDraft } from '../drafts.svelte';
import { decodeImage, encodePng, readImageFile } from './browser-image';
import { canvasSize, containerProfile, type ContainerChoice, type ContainerProfile } from './containers';
import { composeCanvas } from './compose';
import { ART_CAP, clipToCap, insideCap, isEmptyRect, screenRect, unionRects } from './open-canvas';
import { addLayer, patchLayers, setDocumentFields, type Command } from './commands';
import { GuiHistory } from './history';
import {
  drawingProfileOf,
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
  cellsOfPatch,
  clampBrush,
  fillPatch,
  pushRecent,
  workingCanvas,
  type BrushSettings,
  type PixelCells,
} from './paint';
import {
  geometryFields,
  isPaintLayer,
  paintReferenceOf,
  paintRect,
  reshape,
  trimPaintLayers,
} from './paint-geometry';
import { PixelCommand, paintCommand, type PixelStore } from './pixel-command';
import { ArgbCanvas, type Rect } from './raster/canvas';
import type { Argb } from './raster/color';
import { bareType, whole } from './raster/flatten';
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
import { customizeItem, draftOf, guiApiAvailable, validateDraft } from './server';
import { composeUnderlay, veiledScreen, type UnderlayKind } from './underlay';
import { loadUnderlaySheets } from './underlay-sheets';
import { newlyFlagged, switchCost, SWITCH_COST_UNKNOWN } from './switch-cost';
import { isLogicCommand, withBlock } from '../logic/command';
import { LogicLog } from '../logic/log';
import { logicApiAvailable, logicEditResponse } from '../logic/server';
import { blockOf } from '../logic/tree';
import type { ServerDiagnostic } from './server';
import { flagsOf, isSkipped, type LayerFlag } from './validation';
import { centered, fitView, panBy, stepZoom, zoomTo, type Point, type Size, type View } from './viewport';

export type PaintTool = 'brush' | 'eraser' | 'fill' | 'picker';
export type Tool = 'select' | 'hand' | PaintTool;

export function isPaintTool(tool: Tool): tool is PaintTool {
  return tool === 'brush' || tool === 'eraser' || tool === 'fill' || tool === 'picker';
}

const PAINT_STORAGE_KEY = 'materione.gui.paint';
const DEFAULT_PAINT_COLOR: Argb = 0xff3c8527;
/** How long editing must pause before the server is asked for the compiler's verdict. */
const CHECK_PAUSE_MS = 300;
const NO_GUI_API =
  'This server does not offer the GUI API (the gui capability), so Customize is unavailable. Update the Materione plugin to customize items here.';

export class GuiStudioState {
  readonly #history = new GuiHistory(starterDocument());
  /** The logic edits the server has not seen yet (the Logic tab shares this history; logic.md §7.1). */
  readonly #logic = new LogicLog();
  #location: GuiLocation | null = null;
  readonly #images = new Map<string, ArgbCanvas | null>();
  readonly #loading = new Set<string>();
  /** Pixel assets made or edited in this session, by texture reference; sent on save while referenced. */
  readonly #dirtyAssets = new Map<string, ArgbCanvas>();
  /** The original bytes of uploaded images (base64), sent as they were chosen rather than re-encoded. */
  readonly #uploads = new Map<string, string>();
  #stroke: Stroke | null = null;
  /** The layer a stroke is drawing on: its bitmap and box before the stroke, and what its geometry fields were. */
  #strokeLayer: { layerId: string; reference: string; bitmap: ArgbCanvas; rect: Rect; fields: Record<string, unknown> } | null = null;
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
  /** The guides for what a 1920 x 1080 window shows around the screen at GUI scales 2, 3 and 4 (gui.md section 11.5). */
  showGuides = $state(false);
  /** The owner's sheets, once decoded (whichever exist); the underlay draws from them, else from a generated diagram. */
  underlaySheets = $state.raw<ReadonlyMap<string, ArgbCanvas> | null>(null);
  /** The containers the connected server lists (`authoring.describe`, gui.md section 12.4); none from a server too old to list them. */
  containerChoices = $state.raw<readonly ContainerChoice[]>([]);
  /** A container the author chose in the Screen panel and has not applied yet, and the one-line cost of switching to it. */
  pendingContainer = $state<string | null>(null);
  pendingCost = $state('');
  #pendingSeq = 0;
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
  /** The compiler's verdict on the document as shown, by layer id (gui.md §10 #8). */
  flags = $state.raw<Map<string, LayerFlag[]>>(new Map());
  /** Verdict problems about the GUI as a whole (a parse failure, say). */
  documentFlags = $state<string[]>([]);
  /**
   * Whether the server can give the verdict and Customize: `off` before a connection, `unavailable`
   * when it does not advertise the `gui` capability (an older plugin, or the demo), `checking`,
   * `ready`, or `failed` with `checkError` saying why.
   */
  checks = $state<'off' | 'unavailable' | 'checking' | 'ready' | 'failed'>('off');
  checkError = $state('');
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
    void loadUnderlaySheets().then(
      (sheets) => (this.underlaySheets = sheets),
      () => {
        // no sheet at all: every container is drawn as the generated diagram
        this.underlaySheets = new Map();
        this.say('The vanilla art could not be loaded; the canvas shows the generated diagram.');
      },
    );
  }

  /** The document the canvas draws: the live drag preview, else the committed one. */
  shown = $derived(this.preview ?? this.doc);
  readOnly = $derived(this.blocking.length > 0);
  /** The container profile the screen is drawn in (a GUI whose container is unknown is read-only and draws the default). */
  profile = $derived<ContainerProfile>(drawingProfileOf(this.doc));
  size = $derived(canvasSize(this.profile));
  scene = $derived.by<Scene>(() => {
    void this.imageVersion;
    return sceneOf(this.shown, this.registry, (reference) => this.imageFor(reference));
  });
  badges = $derived(orderBadges(this.shown, this.scene));
  order = $derived(compiledOrder(this.shown, this.scene));
  marks = $derived(overlays(this.shown, this.scene));
  texts = $derived(this.marks.filter((mark) => mark.target === 'text'));
  items = $derived(this.marks.filter((mark) => mark.target === 'slot'));
  /** The ids of the layers the compiler drops, from the server's verdict (empty until it answers). */
  skippedIds = $derived(new Set([...this.flags].filter(([, flags]) => isSkipped(flags)).map(([id]) => id)));
  /**
   * The art as the player sees it, over the whole art cap (gui.md section 11.2): raster layers, then smallcaps title
   * text, on a 1024 x 1024 canvas whose top-left is the cap's corner. The eyedropper samples this.
   */
  composite = $derived.by<ArgbCanvas>(() => {
    void this.imageVersion;
    return composeCanvas(this.shown, {
      width: ART_CAP.w,
      height: ART_CAP.h,
      originX: ART_CAP.x,
      originY: ART_CAP.y,
      container: this.profile,
      image: (reference) => this.imageFor(reference),
    }, this.skippedIds);
  });
  /**
   * The container's screen (gui.md sections 11.2 and 12.7): the owner's art composed from the profile's blits when its
   * sheets exist, else a generated diagram. Not a layer, never saved, not in the history. Null until the sheets are known.
   */
  screenBase = $derived.by(() => (this.underlaySheets ? composeUnderlay(this.profile, this.underlaySheets) : null));
  /** Whether the screen under the art is the owner's art or the generated diagram (null until it is known). */
  underlayKind = $derived<UnderlayKind | null>(this.screenBase?.kind ?? null);
  /** The screen as drawn: the underlay with the player's inventory veiled except under painted pixels. */
  underlay = $derived.by<ArgbCanvas | null>(() => {
    const base = this.screenBase;
    if (!base) return null;
    const art = this.composite;
    return veiledScreen(base.screen, this.profile, (x, y) => (art.get(x, y) >>> 24) > 0);
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
    this.#logic.reset();
    this.blocking = result.blocking;
    this.issues = result.issues;
    this.selection = [];
    this.preview = null;
    this.#stroke = null;
    this.#strokeLayer = null;
    this.editingText = null;
    this.choosing = false;
    this.cancelContainer();
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
    if (isLogicCommand(command)) this.#logic.executed(command);
    this.sync();
    return true;
  }

  undo(): void {
    if (this.readOnly) return;
    const command = this.#history.nextUndo;
    this.#history.undo();
    if (isLogicCommand(command)) this.#logic.undone(command);
    this.sync();
  }

  redo(): void {
    if (this.readOnly) return;
    const command = this.#history.nextRedo;
    this.#history.redo();
    if (isLogicCommand(command)) this.#logic.redone(command);
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
      // A paint layer owns its pixels and their box (gui.md section 11.2). It starts with none: nothing is
      // written for it until something is painted.
      const reference = paintReference(this.path, layer.id);
      made = { ...layer, image: reference } as GuiLayer;
      this.#images.set(reference, new ArgbCanvas(0, 0));
      this.imageVersion++;
    }
    if (bareType(layer) === 'item') made = { ...layer, slot: this.freeSlot() } as GuiLayer;
    this.run(addLayer(this.doc, made, this.doc.layers.length, `Add ${info.displayName}`));
    this.selection = [made.id];
    // a new text layer starts in edit mode on the canvas, like a text tool
    if (bareType(made) === 'text') this.editingText = made.id;
  }

  /**
   * Asked before layers are deleted: the Design/Logic linkage answers true when it took the deletion over, because
   * rules name the layers and the author has to say what becomes of them (logic.md section 7.4).
   */
  deleteGuard: ((ids: readonly string[]) => boolean) | null = null;

  deleteSelection(): void {
    if (this.readOnly || this.deleteGuard?.(this.selection)) return;
    this.run(ops.deleteLayers(this.doc, this.selection));
  }

  duplicateSelection(): void {
    const { command, created } = ops.duplicateLayers(this.doc, this.scene, this.selection, (source, copy) => {
      // a copy of a paint layer has pixels of its own: painting on it must not paint on the original
      const original = paintReferenceOf(source);
      const bitmap = isPaintLayer(source) && original ? this.#images.get(original) : null;
      if (!bitmap) return;
      const reference = paintReference(this.path, copy.id);
      copy.image = reference;
      this.#images.set(reference, bitmap.clone());
      if (bitmap.width * bitmap.height > 0) this.#dirtyAssets.set(reference, this.#images.get(reference)!);
    });
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

  // ------------------------------------------------------------------ container

  /** The containers the server lists (`authoring.describe`); an older server lists none and the studio offers none. */
  setContainers(choices: readonly ContainerChoice[]): void {
    this.containerChoices = choices;
  }

  /**
   * The author chose a container in the Screen panel: it is staged, not applied, and the panel states the cost in one line
   * from the server's verdict on this document with the target container (gui.md section 12.7). The studio keeps no
   * rules of its own, so without the server's check the line says the cost is unknown (and that no layer changes).
   */
  stageContainer(id: string, channel: RpcChannel | null): void {
    const seq = ++this.#pendingSeq;
    if (this.readOnly || id === this.doc.container || !containerProfile(id)) {
      this.pendingContainer = null;
      this.pendingCost = '';
      return;
    }
    this.pendingContainer = id;
    this.pendingCost = '';
    void this.#switchCost(id, channel).then((text) => {
      if (seq === this.#pendingSeq) this.pendingCost = text;
    });
  }

  cancelContainer(): void {
    this.#pendingSeq++;
    this.pendingContainer = null;
    this.pendingCost = '';
  }

  async #switchCost(id: string, channel: RpcChannel | null): Promise<string> {
    const label = containerProfile(id)!.label;
    if (!channel || !guiApiAvailable(channel)) return SWITCH_COST_UNKNOWN;
    try {
      const draft = draftOf({ ...this.doc, container: id }, this.rootId, this.pack, this.path);
      const diagnostics = await validateDraft(channel, draft, await this.#unsavedPixels());
      return switchCost(label, newlyFlagged(this.doc.layers, this.flags, flagsOf(diagnostics, this.doc).byLayer));
    } catch (error) {
      return `The server's check failed (${error instanceof Error ? error.message : String(error)}), so what the switch flags is not shown. No layer is changed by the switch.`;
    }
  }

  /**
   * Switches the GUI to another container: the one `container:` line changes, as one undo step, and no layer is moved,
   * resized or deleted (gui.md sections 12.7 and 12.9 item 8). What no longer fits is flagged by the server's verdict
   * like any other layer the compiler skips; the status bar repeats the cost the panel stated.
   */
  switchContainer(id: string): void {
    const profile = containerProfile(id);
    if (this.readOnly || !profile || id === this.doc.container) return;
    const cost = this.pendingContainer === id ? this.pendingCost : '';
    this.run(setDocumentFields(this.doc, { container: id }, `Switch to ${profile.label}`));
    this.cancelContainer();
    this.fit();
    this.say(cost ? `Switched to ${profile.label}. ${cost}` : `Switched to ${profile.label}.`, 8000);
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

  /** Pans over the whole art cap, not just the screen: the canvas is open (gui.md section 11). */
  pan(dx: number, dy: number): void {
    this.setView(panBy(this.view, dx, dy, this.stage, { w: ART_CAP.w, h: ART_CAP.h }, 64, { x: ART_CAP.x, y: ART_CAP.y }));
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
    // a paint layer saved with no pixel (width 0, height 0) has no file: it is an empty bitmap, not a texture to fetch
    const unpainted = new Set(
      this.doc.layers.flatMap((layer) =>
        isPaintLayer(layer) && layer.width === 0 && layer.height === 0 ? [paintReferenceOf(layer)] : []),
    );
    await Promise.all([...wanted].map(async (reference) => {
      if (this.#images.has(reference) || this.#loading.has(reference)) return;
      if (unpainted.has(reference)) {
        this.#images.set(reference, new ArgbCanvas(0, 0));
        this.imageVersion++;
        return;
      }
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

  #paintTarget(): { layer: GuiLayer; reference: string; bitmap: ArgbCanvas } | null {
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
    const reference = paintReferenceOf(layer) || paintReference(this.path, layer.id);
    if (layer.image !== reference) {
      // a paint layer written without a texture: it has no pixels yet
      this.#images.set(reference, new ArgbCanvas(0, 0));
      this.run(ops.setField(this.doc, layer.id, 'image', reference, 'Set paint texture'));
    }
    let bitmap = this.#images.get(reference);
    if (bitmap === undefined) {
      this.say('The layer is still loading.');
      return null;
    }
    if (bitmap === null) {
      // no file: fine for a layer that was never painted, but not for one the document says has pixels
      if (whole(layer.width, 0) > 0 && whole(layer.height, 0) > 0) {
        this.say('This layer\'s pixels could not be read, so painting on it could overwrite them.');
        return null;
      }
      bitmap = new ArgbCanvas(0, 0);
      this.#images.set(reference, bitmap);
    }
    return { layer, reference, bitmap };
  }

  /** The `x`, `y`, `width` and `height` a layer has right now (an absent field is `undefined`), so an undo restores them exactly. */
  #geometryOf(layer: GuiLayer): Record<string, unknown> {
    return Object.fromEntries(
      Object.keys(geometryFields({ x: 0, y: 0, w: 0, h: 0 })).map((key) => [key, Object.hasOwn(layer, key) ? layer[key] : undefined]),
    );
  }

  /**
   * Redraws at most once per frame while a stroke is in flight. The layer on screen is the stroke's own working
   * copy, grown to hold what has been painted, so a stroke that leaves the layer is seen leaving it.
   */
  #kick(): void {
    if (this.#redraw) return;
    const schedule = globalThis.requestAnimationFrame ?? ((callback: () => void) => setTimeout(callback, 16) as unknown as number);
    this.#redraw = schedule(() => {
      this.#redraw = 0;
      this.#showStroke();
      this.imageVersion++;
    });
  }

  #showStroke(): void {
    const stroke = this.#stroke;
    const base = this.#strokeLayer;
    if (!stroke || !base) return;
    const ink = stroke.inkBox();
    const grown = ink ? unionRects(base.rect, ink) : base.rect;
    const working = stroke.canvas;
    this.#images.set(
      base.reference,
      reshape(working, { x: working.originX, y: working.originY, w: working.width, h: working.height }, grown),
    );
    this.preview = patchLayers(this.doc, { [base.layerId]: geometryFields(grown) }, 'Paint')?.apply(this.doc) ?? null;
  }

  #touch(reference: string): void {
    const canvas = this.#images.get(reference);
    if (canvas) this.#dirtyAssets.set(reference, canvas);
    this.imageVersion++;
  }

  readonly #store: PixelStore = {
    get: (reference) => this.#images.get(reference),
    set: (reference, canvas) => void this.#images.set(reference, canvas),
    touched: (reference) => this.#touch(reference),
  };

  /** Begins a brush or eraser stroke at a GUI pixel; shift joins the previous stroke's end with a straight line. */
  beginStroke(point: Point, erase: boolean, straight: boolean): boolean {
    const target = this.#paintTarget();
    if (!target) return false;
    if (!insideCap(point.x, point.y)) {
      this.say('That is outside the art cap: art there is not drawn in game.');
      return false;
    }
    const rect = paintRect(target.layer, target.bitmap);
    // the stroke runs on a copy that covers the whole cap, so it can leave the layer; the copy is clipped to the cap
    const working = workingCanvas(target.bitmap, rect, ART_CAP);
    const from = straight ? this.#lastStrokeEnd.get(target.reference) : undefined;
    this.#stroke = new Stroke(working, target.reference, erase ? ERASER : this.color, this.brush, point, from);
    this.#strokeLayer = {
      layerId: target.layer.id,
      reference: target.reference,
      bitmap: target.bitmap,
      rect,
      fields: this.#geometryOf(target.layer),
    };
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
    const base = this.#strokeLayer;
    this.#stroke = null;
    this.#strokeLayer = null;
    if (!stroke || !base) return;
    this.#lastStrokeEnd.set(stroke.reference, stroke.last);
    // back to the layer as it was before the stroke: the command writes the result in, and undo gives this back
    this.#images.set(base.reference, base.bitmap);
    this.preview = null;
    const patch = stroke.end();
    if (!patch) {
      this.imageVersion++;
      return;
    }
    const layer = findLayer(this.doc, base.layerId);
    if (!layer) return;
    const cells = cellsOfPatch(patch, stroke.canvas);
    this.run(paintCommand(this.#strokeErases ? 'Erase' : 'Paint stroke', layer.id, base.reference, { rect: base.rect, fields: base.fields }, cells, this.#store));
    if (!this.#strokeErases) this.setColor(this.color, true);
  }

  /**
   * Flood fill (contiguous, exact colour match) with the paint colour. The fill is bounded by the layer's box joined
   * with the screen, so it works on the screen's empty space and inside painted shapes, and never floods the art cap.
   */
  fillAt(point: Point): void {
    const target = this.#paintTarget();
    if (!target) return;
    const rect = paintRect(target.layer, target.bitmap);
    const window = clipToCap(unionRects(rect, screenRect(this.profile)));
    if (!window || point.x < window.x || point.y < window.y || point.x >= window.x + window.w || point.y >= window.y + window.h) {
      this.say('Fill works on the screen and inside painted shapes. Use the brush to paint further out.');
      return;
    }
    const working = workingCanvas(target.bitmap, rect, window);
    const patch = fillPatch(working, target.reference, point.x, point.y, this.color);
    if (!patch) {
      this.say('Nothing to fill there.');
      return;
    }
    this.run(paintCommand('Fill', target.layer.id, target.reference, { rect, fields: this.#geometryOf(target.layer) }, cellsOfPatch(patch, working), this.#store));
    this.setColor(this.color, true);
  }

  /** Empties the paint layer (one undoable step): no pixel is left, so none is saved. */
  clearPaint(id: string): void {
    const layer = findLayer(this.doc, id);
    const reference = layer ? paintReferenceOf(layer) : '';
    const bitmap = reference ? this.#images.get(reference) : null;
    if (!layer || !bitmap || this.readOnly || isLocked(layer)) return;
    const rect = paintRect(layer, bitmap);
    const painted: number[] = [];
    bitmap.pixels.forEach((value, index) => value >>> 24 !== 0 && painted.push(index));
    if (isEmptyRect(rect) && painted.length === 0) {
      this.say('The layer is already empty.');
      return;
    }
    const cells: PixelCells = {
      x: Int32Array.from(painted, (index) => (index % bitmap.width) + rect.x),
      y: Int32Array.from(painted, (index) => Math.floor(index / bitmap.width) + rect.y),
      before: Uint32Array.from(painted, (index) => bitmap.pixels[index]!),
      after: new Uint32Array(painted.length),
    };
    const emptied: Rect = { x: rect.x, y: rect.y, w: 0, h: 0 };
    this.run(new PixelCommand(
      'Clear paint layer', id, reference, cells,
      { rect, fields: this.#geometryOf(layer) },
      { rect: emptied, fields: geometryFields(emptied) },
      this.#store,
    ));
  }

  #previousPaintTool: PaintTool = 'brush';

  /**
   * The eyedropper: the colour at a GUI pixel. What Materione draws comes first (the composited art, text included, as the
   * player sees it); where it draws nothing the owner's vanilla art underneath answers, in its own colour and not the veiled one (a generated diagram is not vanilla colour, so it answers nothing).
   */
  pickAt(point: Point): boolean {
    const art = this.composite.get(point.x, point.y);
    const base = this.screenBase?.kind === 'art' ? this.screenBase.screen : null;
    const color = art >>> 24 !== 0 ? art : (base?.get(point.x, point.y) ?? 0);
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
    for (let slot = 0; slot < this.profile.slotCount; slot++) if (!taken.has(slot)) return slot;
    return 0;
  }

  /**
   * Customize (gui.md §10 #2): the server gives the layer its own ordinary item and points the layer
   * at it, in one transaction at the GUI's revision (`gui.customizeItem`). The rules are the server's.
   * Pending changes are saved first, as their own transaction, because the server works on the saved
   * file. Returns the new item's pack-relative path, or null (with `saveError` set) when nothing changed.
   */
  async customize(channel: RpcChannel, draft: DocumentDraft, layerId: string): Promise<string | null> {
    if (!findLayer(this.doc, layerId) || this.readOnly || this.saving || !this.#location) return null;
    if (!guiApiAvailable(channel)) {
      this.saveError = NO_GUI_API;
      return null;
    }
    this.saveError = '';
    if (this.dirty && !(await this.save(channel, draft))) return null;
    this.saving = true;
    try {
      const created = await customizeItem(channel, this.pack, this.path, draft.rev, layerId);
      // the layer now names the item the server made: the same edit, on the studio's copy of the file
      this.run(ops.setField(this.doc, layerId, 'item', created.reference, 'Customize item'));
      this.#history.markSaved();
      this.sync();
      this.notice = 'Created the item and linked this layer to it.';
      await reconcileDocumentDraft(draft, channel);
      this.seenRev = created.rev;
      return created.path;
    } catch (error) {
      if (error instanceof RpcError) {
        this.conflict = error.code === 'MTR-0034';
        this.saveError = error.message; // "CODE: message", as the server reported it
      } else {
        this.saveError = error instanceof Error ? error.message : String(error);
      }
      return null;
    } finally {
      this.saving = false;
    }
  }

  // ------------------------------------------------------------------ the compiler's verdict

  #checkTimer: ReturnType<typeof setTimeout> | undefined;
  #checkSeq = 0;

  /**
   * Asks the server for the compiler's verdict on the document as shown, once editing pauses. Called
   * on every change; a later question makes an earlier answer stale, so only the newest is drawn.
   * Without the `gui` capability there is no verdict, and the studio says so instead of drawing none.
   */
  scheduleCheck(channel: RpcChannel | null): void {
    clearTimeout(this.#checkTimer);
    if (!channel || !this.#location) return;
    if (!guiApiAvailable(channel)) {
      this.checks = 'unavailable';
      this.flags = new Map();
      this.documentFlags = [];
      return;
    }
    this.#checkTimer = setTimeout(() => void this.#check(channel), CHECK_PAUSE_MS);
  }

  async #check(channel: RpcChannel): Promise<void> {
    const seq = ++this.#checkSeq;
    const doc = this.shown;
    this.checks = 'checking';
    try {
      const assets = await this.#unsavedPixels();
      const diagnostics: ServerDiagnostic[] = await validateDraft(
        channel,
        draftOf(doc, this.rootId, this.pack, this.path),
        assets,
      );
      if (seq !== this.#checkSeq) return;
      const verdict = flagsOf(diagnostics, doc);
      this.flags = verdict.byLayer;
      this.documentFlags = verdict.document;
      this.checks = 'ready';
      this.checkError = '';
    } catch (error) {
      if (seq !== this.#checkSeq) return;
      this.checks = 'failed';
      this.checkError = error instanceof Error ? error.message : String(error);
    }
  }

  /** The pixels the draft needs and the saved pack does not have yet: dirty paint and uploads, base64 PNG. */
  async #unsavedPixels(): Promise<AssetUpload[]> {
    const out: AssetUpload[] = [];
    for (const [reference, canvas] of this.#dirtyAssets) {
      if (canvas.width * canvas.height === 0) continue; // a layer with no pixel has no file
      const data = this.#uploads.get(reference) ?? base64Of(await encodePng(canvas));
      out.push({ path: textureAssetPath(reference), data });
    }
    return out;
  }

  /** The compiler's problems with one layer, as the server reported them (none before the first answer). */
  flagsFor(id: string): LayerFlag[] {
    return this.flags.get(id) ?? [];
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
      // Every paint layer is saved trimmed to its painted pixels (gui.md section 11.2): the PNG holds no transparent
      // margin, and a layer with no pixel at all saves no PNG and is written with width 0 and height 0.
      // Only when the layout or a texture is being saved anyway: a save of the title, the container or the logic never rewrites art.
      const savingArt = this.#dirtyAssets.size > 0 || JSON.stringify(this.#history.baseline.layers) !== JSON.stringify(this.#history.doc.layers);
      const trim = savingArt
        ? trimPaintLayers(this.#history.doc, (reference) => this.#images.get(reference), (reference) => this.#dirtyAssets.has(reference))
        : { doc: this.#history.doc, layers: [] };
      const trimmed = new Set(trim.layers.map((entry) => entry.reference));
      const encoded = new Map<string, string>();
      for (const [reference, canvas] of this.#dirtyAssets) {
        if (trimmed.has(reference)) continue;
        if (canvas.width * canvas.height === 0) continue;
        encoded.set(reference, this.#uploads.get(reference) ?? base64Of(await encodePng(canvas)));
      }
      for (const entry of trim.layers) {
        if (entry.needsPng) encoded.set(entry.reference, base64Of(await encodePng(entry.canvas)));
      }
      const plan = planSave(this.#location.prefix, this.#history.baseline, trim.doc, encoded);
      // The Logic tab's edits go through logic.edit, the same ops the canvas planned them with (D6); only a
      // log that fell out of step with the history is saved as the whole behavior and flows fields.
      const savedLogic = blockOf(this.#history.baseline.rest);
      const logicEdits = this.#logic.desynced ? [] : this.#logic.edits();
      if (this.#logic.desynced) {
        const block = blockOf(this.#history.doc.rest);
        for (const key of ['behavior', 'flows'] as const) {
          if (JSON.stringify(block[key]) !== JSON.stringify(savedLogic[key])) {
            plan.edits.push(block[key] === undefined
              ? { field: [...this.#location.prefix, key], value: undefined, remove: true }
              : { field: [...this.#location.prefix, key], value: block[key] });
          }
        }
      }
      if (logicEdits.length > 0 && !logicApiAvailable(channel)) {
        this.saveError = 'This server does not offer the logic API (the logic capability), so logic changes cannot be saved. Update the Materione plugin.';
        return false;
      }
      // What was saved is the trimmed layer; the studio holds the same from now on (no margin, same pixels).
      const adoptTrim = (): void => {
        for (const entry of trim.layers) {
          this.#images.set(entry.reference, entry.canvas);
          this.#dirtyAssets.delete(entry.reference);
        }
        this.#history.adopt(trim.doc);
        this.imageVersion++;
      };
      if (isEmptyPlan(plan) && logicEdits.length === 0) {
        adoptTrim();
        this.#history.markSaved();
        this.#logic.saved();
        this.sync();
        return true;
      }
      const failed = (code: string, message: string, path?: string): false => {
        this.conflict = code === 'MTR-0034';
        this.saveError = this.conflict
          ? 'This GUI changed on the server since you opened it. Your edits are kept here; reload to take the server version.'
          : `${code}: ${message}${path ? ` (at ${path})` : ''}`;
        return false;
      };
      if (!isEmptyPlan(plan)) {
        const response = await sendSave(channel, this.pack, this.path, draft.rev, plan);
        if (!response.ok) return failed(response.error.code, response.error.message);
        adoptTrim();
        this.#history.markSaved();
        for (const asset of plan.assets) {
          const reference = [...encoded.keys()].find((key) => textureAssetPath(key) === asset.path);
          if (reference) {
            this.#dirtyAssets.delete(reference);
            this.#uploads.delete(reference);
          }
        }
        if (logicEdits.length > 0) {
          // the layout is on the server; what is still unsaved is the logic, so it is still what "dirty" measures
          this.#history.rebase(withBlock(this.#history.doc, savedLogic));
          this.sync();
          await reconcileDocumentDraft(draft, channel);
        }
      }
      if (logicEdits.length > 0) {
        const response = await logicEditResponse(channel, this.pack, this.path, draft.rev, logicEdits);
        if (!response.ok) return failed(response.error.code, response.error.message, response.error.resource ?? undefined);
        this.#history.markSaved();
      }
      this.#logic.saved();
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
