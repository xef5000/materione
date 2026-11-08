/**
 * A paint-tool use as an undoable command: one stroke (however many pointer events drew it), one
 * fill, one clear. It changes two things together, so a stroke that leaves the layer and its undo are
 * one step: the layer's bitmap (the touched pixels, as cells at GUI positions) and the layer's
 * geometry (`x`, `y`, `width`, `height` -- the layer grows to hold what was painted, gui.md section 11.2).
 *
 * The history counts these separately from document edits so "is this clean?" stays exact
 * (`GuiHistory.pixelsDirty`): a stroke inside the layer changes no document field, a stroke that grows
 * it changes the geometry too, and undoing either gives back the exact fields (absent ones included).
 */
import { patchLayers, type Command, type LayerPatch } from './commands';
import { findLayer, type GuiDocument } from './model';
import { paintedBox, type PixelCells } from './paint';
import { geometryFields, paintRect, reshape } from './paint-geometry';
import { unionRects } from './open-canvas';
import { ArgbCanvas, type Rect } from './raster/canvas';

/** The bitmaps the command reads and writes; the studio owns them. */
export interface PixelStore {
  get(reference: string): ArgbCanvas | null | undefined;
  set(reference: string, canvas: ArgbCanvas): void;
  /** The texture changed: it is saved with the layout. */
  touched(reference: string): void;
}

/** The layer as it stands at one end of the edit. */
export interface PaintState {
  /** Where the bitmap sits and how big it is (w = h = 0 for a layer with no pixel). */
  rect: Rect;
  /** The layer's geometry fields exactly as they were: absent keys are `undefined`. */
  fields: LayerPatch;
}

export class PixelCommand implements Command {
  readonly pixelAsset: string;

  constructor(
    readonly label: string,
    readonly layerId: string,
    readonly reference: string,
    readonly cells: PixelCells,
    readonly before: PaintState,
    readonly after: PaintState,
    private readonly store: PixelStore,
  ) {
    this.pixelAsset = reference;
  }

  apply(doc: GuiDocument): GuiDocument {
    return this.write('after', doc);
  }

  revert(doc: GuiDocument): GuiDocument {
    return this.write('before', doc);
  }

  private write(direction: 'after' | 'before', doc: GuiDocument): GuiDocument {
    const target = direction === 'after' ? this.after : this.before;
    const layer = findLayer(doc, this.layerId);
    const current = this.store.get(this.reference) ?? new ArgbCanvas(0, 0);
    // where the bitmap really is now, not where this command last saw it: a save may have trimmed it since
    const from = layer ? paintRect(layer, current) : { x: 0, y: 0, w: current.width, h: current.height };
    const next = reshape(current, from, target.rect);
    const values = this.cells[direction];
    for (let k = 0; k < values.length; k++) {
      const x = this.cells.x[k]! - target.rect.x;
      const y = this.cells.y[k]! - target.rect.y;
      if (x >= 0 && y >= 0 && x < next.width && y < next.height) next.pixels[y * next.width + x] = values[k]!;
    }
    this.store.set(this.reference, next);
    this.store.touched(this.reference);
    return patchLayers(doc, { [this.layerId]: target.fields }, this.label)?.apply(doc) ?? doc;
  }
}

/**
 * One tool use as one command: the touched pixels and the layer's geometry before and after. The layer grows to hold
 * what was painted and never shrinks by erasing (the trim is the save's, gui.md section 11.2). A use that leaves the
 * layer's box as it was leaves its fields as they were too (a layer from before the open canvas keeps having none).
 */
export function paintCommand(
  label: string,
  layerId: string,
  reference: string,
  from: PaintState,
  cells: PixelCells,
  store: PixelStore,
): PixelCommand {
  const box = paintedBox(cells);
  const rect = box ? unionRects(from.rect, box) : from.rect;
  const grew = rect.x !== from.rect.x || rect.y !== from.rect.y || rect.w !== from.rect.w || rect.h !== from.rect.h;
  return new PixelCommand(label, layerId, reference, cells, from, { rect, fields: grew ? geometryFields(rect) : from.fields }, store);
}
