/**
 * A Design edit and a Logic edit as one history entry (logic.md section 7.1, 7.4, 33): deleting a layer together with
 * the rules that name it must be one undo step, and its logic must reach the server through `logic.edit` like every
 * other logic change (D6). The Design part is an ordinary command on the layers; the Logic part is the command the
 * canvas builds for the same edits, so the studio's log keeps the edits in step with the history.
 */
import type { Command } from '../../gui/commands';
import type { GuiDocument } from '../../gui/model';
import { buildCommand, type LogicCommand, type LogicEffect } from '../command';
import type { LogicEdit } from '../server';

class Together implements LogicCommand {
  constructor(
    readonly label: string,
    private readonly design: Command | null,
    private readonly rules: LogicCommand,
  ) {}

  get logic(): LogicEffect {
    return this.rules.logic;
  }

  apply(doc: GuiDocument): GuiDocument {
    return this.rules.apply(this.design ? this.design.apply(doc) : doc);
  }

  revert(doc: GuiDocument): GuiDocument {
    const reverted = this.rules.revert(doc);
    return this.design ? this.design.revert(reverted) : reverted;
  }
}

export type Joined = { ok: true; command: Command | null } | { ok: false; reason: string };

/**
 * The one command for a Design change and the logic edits that go with it. With no logic edits it is the Design
 * command itself; a refused edit refuses the whole thing, so nothing is half done.
 */
export function joinWithLogic(doc: GuiDocument, label: string, design: Command | null, edits: readonly LogicEdit[]): Joined {
  if (edits.length === 0) return { ok: true, command: design };
  const built = buildCommand(doc, { ok: true, edits: [...edits], label });
  if (!built.ok) return { ok: false, reason: built.reason };
  if (!built.command) return { ok: true, command: design };
  return { ok: true, command: new Together(label, design, built.command) };
}
