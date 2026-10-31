/**
 * The edits the server has not seen yet (logic.md §8, issue 223). The canvas edits the document locally,
 * like the Design tab, and the save replays what changed through `logic.edit`: one pipeline (D6), and
 * the server's own refusals and compile, not a copy of them here.
 *
 * The log is kept in step with the shared undo history instead of being diffed at save time:
 *
 *   - a new command appends its edits;
 *   - undoing a command that was never saved just drops its pending edits, so a refused edit that the
 *     author undid before saving never reaches the server;
 *   - undoing a command that was already saved appends its inverse (the file has it, so it must be
 *     taken back), and redoing it appends the edits again;
 *   - a save sends the log in order and, once accepted, clears it.
 *
 * If the log ever falls out of step with the history (it must not), it says so and the studio saves the
 * whole `behavior:` and `flows:` through `authoring.setFields` instead, which is also an existing edit op.
 *
 * Pure of the DOM and of Svelte.
 */
import type { LogicCommand } from './command';
import type { LogicEdit } from './server';

interface Entry {
  /** The command the edits belong to; absent for the inverse of a saved command. */
  command?: LogicCommand;
  edits: readonly LogicEdit[];
}

export class LogicLog {
  #entries: Entry[] = [];
  #saved = new WeakSet<LogicCommand>();
  #desynced = false;

  /** Whether anything is waiting to be sent. */
  get pending(): boolean {
    return this.#entries.length > 0 || this.#desynced;
  }

  /** The log fell out of step with the history; the studio saves the whole block instead. */
  get desynced(): boolean {
    return this.#desynced;
  }

  executed(command: LogicCommand): void {
    this.#entries.push({ command, edits: command.logic.edits });
  }

  undone(command: LogicCommand): void {
    if (this.#saved.has(command)) {
      this.#entries.push({ edits: command.logic.inverse });
      return;
    }
    const last = this.#entries[this.#entries.length - 1];
    if (last?.command === command) this.#entries.pop();
    else this.#desynced = true;
  }

  redone(command: LogicCommand): void {
    this.#entries.push(this.#saved.has(command) ? { edits: command.logic.edits } : { command, edits: command.logic.edits });
  }

  /** The edits to send, in order. Consecutive position edits of one node fold into the last. */
  edits(): LogicEdit[] {
    const out: LogicEdit[] = [];
    for (const entry of this.#entries) {
      for (const edit of entry.edits) {
        const previous = out[out.length - 1];
        if (edit.op === 'setNodePosition' && previous?.op === 'setNodePosition' && previous.path === edit.path) out.pop();
        out.push(edit);
      }
    }
    return out;
  }

  /** The server accepted the edits: they are saved, and their commands now need an inverse to be undone. */
  saved(): void {
    for (const entry of this.#entries) if (entry.command) this.#saved.add(entry.command);
    this.#entries = [];
    this.#desynced = false;
  }

  /** A fresh document: nothing pending, nothing known to be saved. */
  reset(): void {
    this.#entries = [];
    this.#saved = new WeakSet();
    this.#desynced = false;
  }
}
