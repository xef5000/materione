/**
 * A paint-tool use as an undoable command: one stroke (however many pointer events drew it), one
 * fill, one clear. The document does not change -- the layer's PNG does -- so `apply` and `revert`
 * write the patch into the layer's canvas and report the asset as touched (so it is saved), and
 * hand the document back untouched. The history counts these separately from document edits so
 * "is this clean?" stays exact (`GuiHistory.pixelsDirty`).
 */
import type { Command } from './commands';
import type { GuiDocument } from './model';
import { applyPatch, type PixelPatch } from './paint';
import type { ArgbCanvas } from './raster/canvas';

export class PixelCommand implements Command {
  readonly pixelAsset: string;

  constructor(
    readonly label: string,
    readonly patch: PixelPatch,
    private readonly canvasOf: (reference: string) => ArgbCanvas | null,
    private readonly touched: (reference: string) => void,
  ) {
    this.pixelAsset = patch.reference;
  }

  apply(doc: GuiDocument): GuiDocument {
    return this.write('after', doc);
  }

  revert(doc: GuiDocument): GuiDocument {
    return this.write('before', doc);
  }

  private write(direction: 'after' | 'before', doc: GuiDocument): GuiDocument {
    const canvas = this.canvasOf(this.patch.reference);
    if (canvas) applyPatch(canvas, this.patch, direction);
    this.touched(this.patch.reference);
    return doc;
  }
}
