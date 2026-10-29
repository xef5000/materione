/**
 * The studio's command stack (gui.md section 6, "History"): undo and redo
 * across property, transform and layer-structure edits. The history owns the
 * current document and the saved baseline, so "is this dirty?" is a structural
 * comparison, and undoing back to the saved state makes the document clean
 * again.
 */
import type { Command } from './commands';
import { sameDocument, type GuiDocument } from './model';

interface Entry {
  command: Command;
  at: number;
}

/** Consecutive same-key edits closer together than this fold into one undo step. */
export const COALESCE_WINDOW_MS = 900;
const LIMIT = 200;

export class GuiHistory {
  #doc: GuiDocument;
  #baseline: GuiDocument;
  #past: Entry[] = [];
  #future: Command[] = [];
  /** The pixel commands currently applied, oldest first: the top identifies the pixel state. */
  #pixels: Command[] = [];
  #savedPixels: Command | null = null;

  constructor(doc: GuiDocument) {
    this.#doc = doc;
    this.#baseline = doc;
  }

  get doc(): GuiDocument {
    return this.#doc;
  }

  get baseline(): GuiDocument {
    return this.#baseline;
  }

  get canUndo(): boolean {
    return this.#past.length > 0;
  }

  get canRedo(): boolean {
    return this.#future.length > 0;
  }

  get undoLabel(): string | null {
    return this.#past[this.#past.length - 1]?.command.label ?? null;
  }

  get redoLabel(): string | null {
    return this.#future[this.#future.length - 1]?.label ?? null;
  }

  /** Differs structurally from the last saved (or loaded) document. */
  get dirty(): boolean {
    return !sameDocument(this.#doc, this.#baseline) || this.pixelsDirty;
  }

  /**
   * The layer pixels differ from what was last saved. A stroke changes no document field, so this
   * is tracked by identity of the newest applied pixel command: undoing back to the saved one
   * (or to the start, when nothing was saved) is clean again, anything else is not.
   */
  get pixelsDirty(): boolean {
    return (this.#pixels[this.#pixels.length - 1] ?? null) !== this.#savedPixels;
  }

  /** Runs a command; null commands (no-op edits) are ignored. Clears redo. */
  execute(command: Command | null, now: number = Date.now()): GuiDocument {
    if (!command) return this.#doc;
    this.#doc = command.apply(this.#doc);
    this.#future = [];
    if (command.pixelAsset) this.#pixels.push(command);
    const last = this.#past[this.#past.length - 1];
    if (last && command.coalesce && last.command.coalesce === command.coalesce &&
        now - last.at <= COALESCE_WINDOW_MS) {
      const merged = last.command.merge?.(command);
      if (merged) {
        this.#past[this.#past.length - 1] = { command: merged, at: now };
        return this.#doc;
      }
    }
    this.#past.push({ command, at: now });
    if (this.#past.length > LIMIT) this.#past.shift();
    return this.#doc;
  }

  undo(): GuiDocument {
    const entry = this.#past.pop();
    if (!entry) return this.#doc;
    this.#doc = entry.command.revert(this.#doc);
    if (entry.command.pixelAsset) this.#pixels.pop();
    this.#future.push(entry.command);
    return this.#doc;
  }

  redo(): GuiDocument {
    const command = this.#future.pop();
    if (!command) return this.#doc;
    this.#doc = command.apply(this.#doc);
    if (command.pixelAsset) this.#pixels.push(command);
    this.#past.push({ command, at: 0 });
    return this.#doc;
  }

  /** The current document becomes the clean baseline (after a successful save). */
  markSaved(): void {
    this.#baseline = this.#doc;
    this.#savedPixels = this.#pixels[this.#pixels.length - 1] ?? null;
    // never fold the next edit into a step made before the save
    this.#past = this.#past.map((entry) => ({ ...entry, at: 0 }));
  }

  /** Replaces the document wholesale (a reload); history and baseline reset. */
  reset(doc: GuiDocument): void {
    this.#doc = doc;
    this.#baseline = doc;
    this.#past = [];
    this.#future = [];
    this.#pixels = [];
    this.#savedPixels = null;
  }
}
