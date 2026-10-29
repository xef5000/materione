/**
 * Every edit the studio makes is one of these invertible commands
 * (gui.md section 6, "History"). A command knows how to apply itself to a
 * document and how to take itself back, and is built from the document it
 * will run on, capturing exactly the state it needs to invert. Documents are
 * immutable and layers are shared between versions, so a command is a few
 * references, not a copy of the GUI.
 *
 * Property edits that repeat (arrow-key nudges, a scrubbed number) carry a
 * `coalesce` key; the history folds consecutive ones into a single undo step.
 */
import {
  layerIndex,
  type GuiDocument,
  type GuiLayer,
  type Json,
} from './model';

type DocFields = { title?: string; rows?: number };

export interface Command {
  readonly label: string;
  /** Consecutive commands with the same key (within the history's window) become one step. */
  readonly coalesce?: string;
  /**
   * Set by commands that change a layer's pixels (a texture reference), not the document: the
   * history tracks those separately so a stroke makes the studio dirty and undoing it makes it clean.
   */
  readonly pixelAsset?: string;
  apply(doc: GuiDocument): GuiDocument;
  revert(doc: GuiDocument): GuiDocument;
  /** Folds a later command of the same kind into this one, or null when it cannot. */
  merge?(next: Command): Command | null;
}

/** A patch value of `undefined` removes the key (an optional field cleared). */
export type LayerPatch = Json;

function withLayers(doc: GuiDocument, layers: readonly GuiLayer[]): GuiDocument {
  return { ...doc, layers };
}

function patched(layer: GuiLayer, patch: LayerPatch): GuiLayer {
  const next: Json = { ...layer };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) delete next[key];
    else next[key] = value;
  }
  return next as GuiLayer;
}

function applyPatches(doc: GuiDocument, patches: Record<string, LayerPatch>): GuiDocument {
  return withLayers(doc, doc.layers.map((layer) => {
    const patch = patches[layer.id];
    return patch ? patched(layer, patch) : layer;
  }));
}

/** The keys a patch touches, with their current values (`undefined` when absent). */
function capture(layer: GuiLayer, patch: LayerPatch): LayerPatch {
  const before: LayerPatch = {};
  for (const key of Object.keys(patch)) before[key] = Object.hasOwn(layer, key) ? layer[key] : undefined;
  return before;
}

class PatchCommand implements Command {
  constructor(
    readonly label: string,
    readonly before: Record<string, LayerPatch>,
    readonly after: Record<string, LayerPatch>,
    readonly coalesce?: string,
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    return applyPatches(doc, this.after);
  }

  revert(doc: GuiDocument): GuiDocument {
    return applyPatches(doc, this.before);
  }

  merge(next: Command): Command | null {
    if (!(next instanceof PatchCommand) || next.coalesce !== this.coalesce || !this.coalesce) return null;
    const before: Record<string, LayerPatch> = { ...this.before };
    const after: Record<string, LayerPatch> = { ...this.after };
    for (const [id, patch] of Object.entries(next.after)) {
      before[id] = { ...next.before[id], ...before[id] }; // the oldest value of each key wins
      after[id] = { ...after[id], ...patch };
    }
    return new PatchCommand(next.label, before, after, this.coalesce);
  }
}

/** Sets fields on one or more layers at once (a move, a resize, an inspector edit, visibility, a rename). */
export function patchLayers(
  doc: GuiDocument,
  patches: Record<string, LayerPatch>,
  label: string,
  coalesce?: string,
): Command | null {
  const before: Record<string, LayerPatch> = {};
  const after: Record<string, LayerPatch> = {};
  for (const [id, patch] of Object.entries(patches)) {
    const index = layerIndex(doc, id);
    if (index < 0) continue;
    const layer = doc.layers[index]!;
    const changed: LayerPatch = {};
    for (const [key, value] of Object.entries(patch)) {
      const same = Object.hasOwn(layer, key)
        ? JSON.stringify(layer[key]) === JSON.stringify(value)
        : value === undefined;
      if (!same) changed[key] = value;
    }
    if (!Object.keys(changed).length) continue;
    before[id] = capture(layer, changed);
    after[id] = changed;
  }
  return Object.keys(after).length ? new PatchCommand(label, before, after, coalesce) : null;
}

export interface Placed {
  layer: GuiLayer;
  index: number;
}

class InsertCommand implements Command {
  constructor(
    readonly label: string,
    private readonly entries: readonly Placed[],
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    const layers = [...doc.layers];
    for (const { layer, index } of [...this.entries].sort((a, b) => a.index - b.index)) {
      layers.splice(Math.min(index, layers.length), 0, layer);
    }
    return withLayers(doc, layers);
  }

  revert(doc: GuiDocument): GuiDocument {
    const ids = new Set(this.entries.map((entry) => entry.layer.id));
    return withLayers(doc, doc.layers.filter((layer) => !ids.has(layer.id)));
  }
}

class RemoveCommand implements Command {
  constructor(
    readonly label: string,
    private readonly entries: readonly Placed[],
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    const ids = new Set(this.entries.map((entry) => entry.layer.id));
    return withLayers(doc, doc.layers.filter((layer) => !ids.has(layer.id)));
  }

  revert(doc: GuiDocument): GuiDocument {
    const layers = [...doc.layers];
    for (const { layer, index } of [...this.entries].sort((a, b) => a.index - b.index)) {
      layers.splice(Math.min(index, layers.length), 0, layer);
    }
    return withLayers(doc, layers);
  }
}

/** Inserts layers at list positions (index into the resulting list, applied in ascending order). */
export function insertLayers(label: string, entries: readonly Placed[]): Command {
  return new InsertCommand(label, entries);
}

export function addLayer(doc: GuiDocument, layer: GuiLayer, at: number = doc.layers.length, label = 'Add layer'): Command {
  return insertLayers(label, [{ layer, index: Math.min(Math.max(0, at), doc.layers.length) }]);
}

export function removeLayers(doc: GuiDocument, ids: readonly string[], label = 'Delete layers'): Command | null {
  const entries: Placed[] = [];
  doc.layers.forEach((layer, index) => {
    if (ids.includes(layer.id)) entries.push({ layer, index });
  });
  return entries.length ? new RemoveCommand(label, entries) : null;
}

class OrderCommand implements Command {
  constructor(
    readonly label: string,
    private readonly before: readonly string[],
    private readonly after: readonly string[],
  ) {}

  private arrange(doc: GuiDocument, order: readonly string[]): GuiDocument {
    const byId = new Map(doc.layers.map((layer) => [layer.id, layer] as const));
    const layers = order.map((id) => byId.get(id)).filter((layer): layer is GuiLayer => layer !== undefined);
    // layers the order does not know (cannot happen through the API) keep their place at the top
    for (const layer of doc.layers) if (!order.includes(layer.id)) layers.push(layer);
    return withLayers(doc, layers);
  }

  apply(doc: GuiDocument): GuiDocument {
    return this.arrange(doc, this.after);
  }

  revert(doc: GuiDocument): GuiDocument {
    return this.arrange(doc, this.before);
  }
}

/**
 * Moves the given layers (kept in their relative order) so the first of them
 * sits at list index `to` of the list without them: dragging in the layers
 * panel, or raise/lower.
 */
export function moveLayers(doc: GuiDocument, ids: readonly string[], to: number, label = 'Reorder layers'): Command | null {
  const moving = doc.layers.filter((layer) => ids.includes(layer.id));
  if (!moving.length) return null;
  const rest = doc.layers.filter((layer) => !ids.includes(layer.id));
  const at = Math.min(Math.max(0, to), rest.length);
  const next = [...rest.slice(0, at), ...moving, ...rest.slice(at)];
  const before = doc.layers.map((layer) => layer.id);
  const after = next.map((layer) => layer.id);
  return before.every((id, index) => id === after[index]) ? null : new OrderCommand(label, before, after);
}

class DocFieldsCommand implements Command {
  constructor(
    readonly label: string,
    private readonly before: DocFields,
    private readonly after: DocFields,
    readonly coalesce?: string,
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    return { ...doc, ...this.after };
  }

  revert(doc: GuiDocument): GuiDocument {
    return { ...doc, ...this.before };
  }

  merge(next: Command): Command | null {
    if (!(next instanceof DocFieldsCommand) || !this.coalesce || next.coalesce !== this.coalesce) return null;
    return new DocFieldsCommand(next.label, { ...next.before, ...this.before }, { ...this.after, ...next.after }, this.coalesce);
  }
}

/** Title or row-count edits (the canvas height follows `rows`). */
export function setDocumentFields(
  doc: GuiDocument,
  fields: DocFields,
  label: string,
  coalesce?: string,
): Command | null {
  const before: DocFields = {};
  const after: DocFields = {};
  if (fields.title !== undefined && fields.title !== doc.title) {
    before.title = doc.title;
    after.title = fields.title;
  }
  if (fields.rows !== undefined && fields.rows !== doc.rows) {
    before.rows = doc.rows;
    after.rows = fields.rows;
  }
  return Object.keys(after).length ? new DocFieldsCommand(label, before, after, coalesce) : null;
}

class CompositeCommand implements Command {
  constructor(
    readonly label: string,
    private readonly steps: readonly Command[],
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    return this.steps.reduce((current, step) => step.apply(current), doc);
  }

  revert(doc: GuiDocument): GuiDocument {
    return [...this.steps].reverse().reduce((current, step) => step.revert(current), doc);
  }
}

/** Several commands as one undo step, applied in order (and reverted in reverse). Null when empty. */
export function compose(label: string, steps: readonly (Command | null)[]): Command | null {
  const real = steps.filter((step): step is Command => step !== null);
  if (!real.length) return null;
  return real.length === 1 && real[0]!.label === label ? real[0]! : new CompositeCommand(label, real);
}
