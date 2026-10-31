/**
 * A logic edit as one entry of the studio's history (gui.md section 6, "History"; logic.md §7.1: the undo
 * history is shared across tabs). The Design and Logic tabs edit the same document through the same
 * stack, so Ctrl+Z undoes whatever was done last, wherever it was done.
 *
 * A logic command holds the logic block before and after, so applying and reverting are exact, and the
 * `LogicEdit`s that made the change with the edits that take it back. The studio replays those edits on
 * the server when the document is saved (`logic.edit`, D6: one pipeline), and an undo of something already
 * saved replays the inverse; an undo of something not yet saved just drops the pending edits.
 */
import type { Command } from '../gui/commands';
import type { GuiDocument } from '../gui/model';
import type { Plan } from './plan';
import type { LogicEdit } from './server';
import { applyWithInverse, blockOf, LogicEditRefused, type LogicBlock } from './tree';

export interface LogicEffect {
  edits: readonly LogicEdit[];
  inverse: readonly LogicEdit[];
}

/** A history command that carries the server edits it stands for. */
export interface LogicCommand extends Command {
  readonly logic: LogicEffect;
}

export function isLogicCommand(command: Command | null | undefined): command is LogicCommand {
  return !!command && 'logic' in command && !!(command as LogicCommand).logic;
}

/** The document with its logic replaced. Keys the block lacks are removed; the others keep their place. */
export function withBlock(doc: GuiDocument, block: LogicBlock): GuiDocument {
  const rest: Record<string, unknown> = { ...doc.rest };
  for (const key of ['behavior', 'flows'] as const) {
    if (block[key] === undefined) delete rest[key];
    else rest[key] = block[key];
  }
  return { ...doc, rest };
}

class Edit implements LogicCommand {
  constructor(
    readonly label: string,
    readonly before: LogicBlock,
    readonly after: LogicBlock,
    readonly logic: LogicEffect,
  ) {}

  apply(doc: GuiDocument): GuiDocument {
    return withBlock(doc, this.after);
  }

  revert(doc: GuiDocument): GuiDocument {
    return withBlock(doc, this.before);
  }
}

export type Built = { ok: true; command: LogicCommand | null } | { ok: false; reason: string };

/**
 * The history command for an accepted plan, built from the document it will run on. An empty plan (a move
 * to where the node already is) builds nothing. Edits that do not apply are refused with the reason the
 * server would give: a plan is made from the graph the document projects to, so that is a bug to see, not hide.
 */
export function buildCommand(doc: GuiDocument, plan: Plan): Built {
  if (!plan.ok) return { ok: false, reason: plan.reason };
  if (plan.edits.length === 0) return { ok: true, command: null };
  const before = blockOf(doc.rest);
  try {
    const { block, inverse } = applyWithInverse(before, plan.edits);
    return { ok: true, command: new Edit(plan.label, before, block, { edits: plan.edits, inverse }) };
  } catch (error) {
    if (error instanceof LogicEditRefused) return { ok: false, reason: error.message };
    throw error;
  }
}
