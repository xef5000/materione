/**
 * The Script tab's reactive core (logic.md section 7.1, 33): the text the author is editing and what becomes of it. The
 * tab has no model of its own. The text is the YAML of the document's `open:`, `behavior:` and `flows:`; when it parses
 * and differs from the document it is committed as one history command (the one history the Design and Logic tabs share),
 * and when the document changes under it (a Design or Logic edit, an undo) the text is read again from the document.
 *
 * A draft that does not parse, or that the logic edits refuse, is never committed and never lost: it stays in the editor
 * with the error beside the line. The compiler is asked about every draft that parses (`logic.validate`), once typing
 * pauses, and its step-path diagnostics are drawn on the lines they name.
 */
import { untrack } from 'svelte';
import type { RpcChannel } from '../../api';
import type { GuiStudioState } from '../../gui/studio.svelte';
import type { LogicCanvasState } from '../canvas.svelte';
import { logicApiAvailable, logicValidate, type LogicDiagnostic } from '../server';
import { buildScriptCommand } from './command';
import {
  blockOfSections, formatScript, lineOfPath, parseScript, sectionsKey, sectionsOf, type ScriptError, type ScriptSections,
} from './sections';

/** How long typing must pause before the draft is committed, and before the compiler is asked. */
const COMMIT_PAUSE_MS = 700;
const CHECK_PAUSE_MS = 450;

export interface ScriptDiagnostic {
  code: string;
  severity: 'error' | 'warn' | 'info';
  message: string;
  /** The 1-based line of the text the step path is on. */
  line: number;
  path: string;
}

export class ScriptState {
  readonly studio: GuiStudioState;
  readonly logic: LogicCanvasState;
  #commitTimer: ReturnType<typeof setTimeout> | undefined;
  #checkTimer: ReturnType<typeof setTimeout> | undefined;
  #serial = 0;
  /** The text the document produced when the editor last agreed with it; the draft is what differs from this. */
  #base = '';
  /** The sections key the editor last agreed with: a document that moves on from it was changed by something else. */
  #seen = '';
  #started = false;

  text = $state('');
  /** Why the draft is not in the document: a syntax error, a section of the wrong shape, or a refusal. */
  error = $state.raw<ScriptError | null>(null);
  /** The document changed in another tab while a draft that was not committed was being edited. */
  stale = $state(false);
  diagnostics = $state.raw<ScriptDiagnostic[]>([]);
  checking = $state(false);
  /** The key of the document's sections now, so the tab reads the text again when something else changes them. */
  docKey: string;

  constructor(studio: GuiStudioState, logic: LogicCanvasState) {
    this.studio = studio;
    this.logic = logic;
    this.docKey = $derived(sectionsKey(studio.doc.rest));
  }

  /** Whether the editor can commit: the server answers the logic API, and the file can be edited here. */
  get editable(): boolean {
    return !this.studio.readOnly && this.logic.status !== 'unavailable' && this.logic.problems.length === 0;
  }

  /** The text is not what the document produced: there is a draft. */
  get drafting(): boolean {
    return this.text !== this.#base;
  }

  // ------------------------------------------------------------------ following the document

  /** Called whenever the document's sections may have changed (and once when the tab opens). */
  follow(): void {
    const key = this.docKey;
    if (!this.#started) {
      this.#started = true;
      this.#reset(key);
      return;
    }
    if (key === this.#seen) return;
    if (!this.drafting) this.#reset(key);
    else {
      this.stale = true;
      this.#seen = key;
    }
  }

  #reset(key: string): void {
    clearTimeout(this.#commitTimer);
    this.#base = formatScript(sectionsOf(this.studio.doc.rest));
    this.text = this.#base;
    this.#seen = key;
    this.error = null;
    this.stale = false;
    clearTimeout(this.#checkTimer);
    this.#checkTimer = setTimeout(() => void this.#check(), CHECK_PAUSE_MS);
  }

  /** Throws the draft away and shows the document. */
  discard(): void {
    this.#reset(this.docKey);
  }

  // ------------------------------------------------------------------ editing

  edit(text: string): void {
    this.text = text;
    this.#parse();
    this.#schedule();
  }

  #parse(): ScriptSections | null {
    const parsed = parseScript(this.text);
    this.error = parsed.ok ? null : parsed.error;
    return parsed.ok ? parsed.sections : null;
  }

  #schedule(): void {
    clearTimeout(this.#commitTimer);
    clearTimeout(this.#checkTimer);
    this.#commitTimer = setTimeout(() => this.commit(), COMMIT_PAUSE_MS);
    this.#checkTimer = setTimeout(() => void this.#check(), CHECK_PAUSE_MS);
  }

  /** Commits the draft now, if it parses, is not stale and the document can take it. Returns whether the text is in the document. */
  commit(force = false): boolean {
    clearTimeout(this.#commitTimer);
    if (!this.drafting) return true; // nothing was typed: the document is never rewritten from its own text
    const sections = this.#parse();
    if (!sections) return false;
    if (this.stale && !force) return false;
    if (!this.editable) return false;
    const built = buildScriptCommand(this.studio.doc, sections);
    if (!built.ok) {
      const line = built.path ? lineOfPath(this.text, built.path.split('.')) : null;
      this.error = { message: built.reason, line: line ?? 1, column: 1 };
      return false;
    }
    if (built.command) this.studio.run(built.command);
    // the editor and the document agree now: the text is kept as written (its comments and layout), not regenerated
    this.#base = this.text;
    this.#seen = untrack(() => this.docKey);
    this.stale = false;
    return true;
  }

  /** Commits any pending draft before a history move, so Undo takes back the author's last edit and not an older one. */
  flush(): void {
    if (this.drafting && !this.stale) this.commit();
  }

  undo(): void {
    this.flush();
    this.studio.undo();
  }

  redo(): void {
    this.flush();
    this.studio.redo();
  }

  // ------------------------------------------------------------------ live validation

  /** Asks the compiler about the draft once typing pauses; its diagnostics are drawn on the lines their step paths name. */
  async #check(): Promise<void> {
    const serial = ++this.#serial;
    const channel = this.#channel();
    const sections = this.error ? null : (() => {
      const parsed = parseScript(this.text);
      return parsed.ok ? parsed.sections : null;
    })();
    if (!channel || !sections) {
      this.diagnostics = [];
      this.checking = false;
      return;
    }
    const block = blockOfSections(sections);
    this.checking = true;
    try {
      const found = await logicValidate(channel, {
        pack: this.studio.pack,
        resource: this.studio.rootId,
        behavior: block.behavior ?? [],
        flows: block.flows ?? {},
      });
      if (serial === this.#serial) this.diagnostics = this.#placed(found);
    } catch {
      if (serial === this.#serial) this.diagnostics = [];
    } finally {
      if (serial === this.#serial) this.checking = false;
    }
  }

  #placed(found: readonly LogicDiagnostic[]): ScriptDiagnostic[] {
    return found.map((diagnostic) => {
      const path = diagnostic.field ?? [];
      return {
        code: diagnostic.code,
        severity: diagnostic.severity === 'error' || diagnostic.severity === 'info' ? diagnostic.severity : 'warn',
        message: diagnostic.message,
        line: lineOfPath(this.text, path) ?? 1,
        path: path.join('.'),
      };
    });
  }

  #connected: RpcChannel | null = null;

  /** The channel the checks go through; set by the tab from the session. */
  connect(channel: RpcChannel | null): void {
    this.#connected = channel && logicApiAvailable(channel) ? channel : null;
    if (this.#started) {
      clearTimeout(this.#checkTimer);
      this.#checkTimer = setTimeout(() => void this.#check(), CHECK_PAUSE_MS);
    }
  }

  #channel(): RpcChannel | null {
    return this.#connected;
  }

  /** Lets go of the timers when the studio closes. */
  dispose(): void {
    clearTimeout(this.#commitTimer);
    clearTimeout(this.#checkTimer);
  }
}
