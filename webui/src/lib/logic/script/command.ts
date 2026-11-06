/**
 * A Script tab commit as one entry of the studio's history (logic.md section 7.1, 33). The tab edits the same document
 * the Design and Logic tabs edit, so a commit is a history command like theirs: Ctrl+Z from any tab undoes it, and Save
 * replays it. The logic part (`behavior:`, `flows:`) is carried as `LogicEdit`s with their inverse, exactly as a canvas
 * gesture is, so it reaches the server through `logic.edit` (D6). The `open:` block is not a logic edit: it is a plain
 * field of the GUI, saved with the layers (`gui/save.ts`).
 */
import type { GuiDocument } from '../../gui/model';
import type { LogicCommand, LogicEffect } from '../command';
import { applyWithInverse, blockOf, LogicEditRefused } from '../tree';
import { blockOfSections, mergeSections, planBlockChange, sectionsOf, sameSections, type ScriptSections } from './sections';

class ScriptEdit implements LogicCommand {
  readonly label = 'Edit script';

  constructor(
    private readonly before: ScriptSections,
    private readonly after: ScriptSections,
    readonly logic: LogicEffect,
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    return { ...doc, rest: mergeSections(doc.rest, this.after) };
  }

  revert(doc: GuiDocument): GuiDocument {
    return { ...doc, rest: mergeSections(doc.rest, this.before) };
  }
}

export type ScriptBuilt =
  | { ok: true; command: LogicCommand | null }
  | { ok: false; reason: string; path?: string };

/** An empty list or mapping is the same as no key for the edits' bookkeeping: removing every rule leaves `behavior: []`. */
const normal = (block: ReturnType<typeof blockOf>): string =>
  JSON.stringify({
    behavior: block.behavior && block.behavior.length > 0 ? block.behavior : undefined,
    flows: block.flows && Object.keys(block.flows).length > 0 ? block.flows : undefined,
  });

/**
 * The command that makes the document hold `after`, or null when it already does. Refused with the server's own
 * reason when the logic edits do not apply (a rule with no `on:`, a step that names two kinds): the refusal names the
 * step path, so the tab can point at the line.
 */
export function buildScriptCommand(doc: GuiDocument, after: ScriptSections): ScriptBuilt {
  const before = sectionsOf(doc.rest);
  if (sameSections(before, after)) return { ok: true, command: null };
  const edits = planBlockChange(blockOf(doc.rest), blockOfSections(after));
  try {
    const applied = applyWithInverse(blockOf(doc.rest), edits);
    if (normal(applied.block) !== normal(blockOfSections(after))) {
      return { ok: false, reason: 'The script could not be expressed as logic edits; nothing was changed.' };
    }
    return { ok: true, command: new ScriptEdit(before, after, { edits, inverse: applied.inverse }) };
  } catch (error) {
    if (error instanceof LogicEditRefused) return { ok: false, reason: error.message, path: error.path };
    throw error;
  }
}
