/**
 * The GUI studio's reactive core: one object that owns the document history,
 * the selection, the camera, the pixel assets and the save, so the canvas, the
 * layers panel, the palette and the inspector are views over the same state.
 * The rules live in the pure modules next to it (commands, scene, snap, ...);
 * this class only wires them to Svelte runes.
 */
import { assetOps, unwrap, type RpcChannel } from '../api';
import { reconcileDocumentDraft, type DocumentDraft } from '../drafts.svelte';
import { decodeImage, encodePng } from './browser-image';
import { canvasSize } from './chest';
import { addLayer, compose, patchLayers, setDocumentFields, type Command } from './commands';
import { GuiHistory } from './history';
import {
  findLayer,
  isLocked,
  parseGui,
  starterDocument,
  type GuiDocument,
  type GuiLayer,
  type GuiLocation,
} from './model';
import * as ops from './ops';
import { ArgbCanvas } from './raster/canvas';
import { componentOf, type GuiComponentInfo, type GuiRegistry } from './registry';
import { base64Of, isEmptyPlan, paintReference, planSave, sendSave, textureAssetPath } from './save';
import type { Guide } from './snap';
import { compiledOrder, layerBounds, orderBadges, placeholders, sceneOf, type Scene } from './scene';
import { centered, fitView, panBy, stepZoom, zoomTo, type Point, type Size, type View } from './viewport';

export type Tool = 'select' | 'hand';

export class GuiStudioState {
  readonly #history = new GuiHistory(starterDocument());
  #location: GuiLocation | null = null;
  readonly #images = new Map<string, ArgbCanvas | null>();
  readonly #loading = new Set<string>();
  /** Pixel assets made or edited in this session, by texture reference; sent on save while referenced. */
  readonly #dirtyAssets = new Map<string, ArgbCanvas>();

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
  ) {}

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
  marked = $derived(placeholders(this.shown, this.scene));
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
    this.#dirtyAssets.clear();
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
    this.dirty = this.#history.dirty || this.#assetsPending();
    this.canUndo = this.#history.canUndo;
    this.canRedo = this.#history.canRedo;
    this.undoLabel = this.#history.undoLabel;
    this.redoLabel = this.#history.redoLabel;
    const ids = new Set(this.#history.doc.layers.map((layer) => layer.id));
    if (this.selection.some((id) => !ids.has(id))) this.selection = this.selection.filter((id) => ids.has(id));
  }

  #assetsPending(): boolean {
    return this.#dirtyAssets.size > 0 && this.#history.doc.layers.some(
      (layer) => typeof layer.image === 'string' && this.#dirtyAssets.has(layer.image),
    );
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
    if (info.id === 'paint') {
      // A paint layer owns a canvas-sized PNG; it exists from the first moment, blank, and is written on save.
      const reference = paintReference(this.path, layer.id);
      made = { ...layer, image: reference } as GuiLayer;
      const blank = new ArgbCanvas(this.size.width, this.size.height);
      this.#images.set(reference, blank);
      this.#dirtyAssets.set(reference, blank);
      this.imageVersion++;
    }
    this.run(addLayer(this.doc, made, this.doc.layers.length, `Add ${info.displayName}`));
    this.selection = [made.id];
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
      if (layer.type === 'panel' && layer.x === 0 && layer.y === 0 && layer.width === from.width && layer.height === from.height) {
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
        encoded.set(reference, base64Of(await encodePng(canvas)));
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
        if (reference) this.#dirtyAssets.delete(reference);
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
