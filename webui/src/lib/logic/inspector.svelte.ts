/**
 * The node inspector's reactive core (logic.md section 7.3, 31): the scope a template is checked against, the
 * declared variables, the draft verdict while a field is being typed, and the one way an inspector edit reaches the
 * document. It holds no logic of its own and no model: an edit is a plan (`edit-plan.ts`) run through the canvas, which
 * makes it one history command and one set of `LogicEdit`s for the save (D6, section 26 decision 4).
 */
import { RpcError, type RpcChannel } from '../api';
import { refreshPack, session, workspace } from '../state.svelte';
import type { LogicCanvasState } from './canvas.svelte';
import { attribute, type Attributed } from './diagnose';
import { planCondition, planRenameFlow, planStepKey, planStepValue } from './edit-plan';
import type { Plan } from './plan';
import { project, type GraphNode } from './project';
import { scopeFor, type TemplateScope } from './scope';
import { logicValidate } from './server';
import { applyEdits, LogicEditRefused } from './tree';
import { verdictsOf, type NodeFlag } from './verdicts';
import { declareVariable, loadDeclared, type DeclaredVariable, type VariableSpec } from './variables';

/** How long typing must pause before the draft is sent to the compiler. */
const DRAFT_PAUSE_MS = 350;
const OPEN_KEY = 'materione.logic.inspector.open';

/** Whether the panel was left open. A per-viewer convenience only: the page works with storage blocked. */
function remembered(): boolean {
  try {
    return localStorage.getItem(OPEN_KEY) !== 'false';
  } catch {
    return true;
  }
}

export class InspectorState {
  readonly canvas: LogicCanvasState;
  #timer: ReturnType<typeof setTimeout> | undefined;
  #serial = 0;

  /** Whether the panel is open. It stays open with nothing selected so the canvas never reflows under the author. */
  open = $state(remembered());
  declared = $state.raw<DeclaredVariable[]>([]);
  declaredStatus = $state<'idle' | 'loading' | 'ready'>('idle');
  /** The compiler's verdict on what is being typed (not in the document yet), for one node. */
  draftFlags = $state.raw<{ node: string; flags: NodeFlag[] } | null>(null);
  scope: TemplateScope;

  constructor(canvas: LogicCanvasState) {
    this.canvas = canvas;
    this.scope = $derived(
      scopeFor(canvas.graph, canvas.block, canvas.selected, canvas.vocabulary, this.declared, canvas.host, canvas.gui.doc.layers as never),
    );
  }

  toggle(): void {
    this.open = !this.open;
    try {
      localStorage.setItem(OPEN_KEY, String(this.open));
    } catch {
      // storage can be blocked; the panel still works
    }
  }

  // ------------------------------------------------------------------ declared variables

  /** Reads the packs' variables files. Called when the inspector first needs them, and again after a declaration. */
  async loadDeclared(channel: RpcChannel | null): Promise<void> {
    if (!channel || this.declaredStatus === 'loading') return;
    this.declaredStatus = 'loading';
    try {
      this.declared = await loadDeclared(channel, workspace.resources);
    } finally {
      this.declaredStatus = 'ready';
    }
  }

  /**
   * Declares a variable in the pack's variables file through the authoring ops and refreshes what the picker lists.
   * Throws the server's message when it refuses (a name already declared, a revision that moved).
   */
  async declare(channel: RpcChannel | null, spec: VariableSpec): Promise<DeclaredVariable> {
    if (!channel) throw new Error('Not connected to a server.');
    try {
      const made = await declareVariable(channel, this.canvas.gui.pack, spec, workspace.resources);
      await refreshPack();
      this.declared = [...this.declared.filter((variable) => variable.id !== made.id), made];
      return made;
    } catch (error) {
      throw error instanceof RpcError ? new Error(error.message) : error;
    }
  }

  // ------------------------------------------------------------------ editing

  /** Runs a plan as one history command; a refused plan says why in the canvas's status line. */
  commit(plan: Plan): boolean {
    this.clearDraft();
    return this.canvas.run(plan);
  }

  setPayload(node: GraphNode, payload: unknown): boolean {
    return this.commit(planStepValue(this.canvas.graph, node.id, payload, this.canvas.nameOf));
  }

  setKey(node: GraphNode, key: string, value: unknown): boolean {
    return this.commit(planStepKey(this.canvas.graph, node.id, key, value, this.canvas.nameOf));
  }

  setCondition(node: GraphNode, condition: unknown, caseIndex?: number): boolean {
    return this.commit(planCondition(this.canvas.graph, this.canvas.block, node.id, condition, this.canvas.nameOf, caseIndex));
  }

  rename(node: GraphNode, name: string): boolean {
    return this.commit(planRenameFlow(this.canvas.graph, node.id, name));
  }

  // ------------------------------------------------------------------ the draft verdict

  /**
   * Asks the compiler about a plan before it is committed, once typing pauses, so a template shows its problem while
   * it is being written. The document is not touched: the plan is applied to a copy and sent to `logic.validate`.
   */
  draft(nodeId: string, plan: Plan): void {
    clearTimeout(this.#timer);
    const canvas = this.canvas;
    const channel = session.channel;
    if (!channel || !plan.ok || canvas.status !== 'ready' || canvas.problems.length > 0) return;
    this.#timer = setTimeout(() => void this.#validate(channel, nodeId, plan), DRAFT_PAUSE_MS);
  }

  async #validate(channel: RpcChannel, nodeId: string, plan: Plan): Promise<void> {
    if (!plan.ok) return;
    const serial = ++this.#serial;
    try {
      const block = applyEdits(this.canvas.block, plan.edits);
      const diagnostics = await logicValidate(channel, {
        pack: this.canvas.gui.pack,
        resource: this.canvas.gui.rootId,
        behavior: block.behavior ?? [],
        flows: block.flows ?? {},
      });
      if (serial !== this.#serial) return;
      const graph = project(block, { takesElse: (action) => this.canvas.vocabulary?.actions.get(action)?.takesElse === true });
      this.draftFlags = { node: nodeId, flags: verdictsOf(graph, diagnostics).get(nodeId) ?? [] };
    } catch (error) {
      if (!(error instanceof LogicEditRefused) && serial === this.#serial) this.draftFlags = null;
    }
  }

  clearDraft(): void {
    clearTimeout(this.#timer);
    this.#serial++;
    this.draftFlags = null;
  }

  /** What the compiler says about a node: the draft while it is being typed, else the document's verdict. */
  flagsFor(node: GraphNode): NodeFlag[] {
    if (this.draftFlags?.node === node.id) return this.draftFlags.flags;
    return this.canvas.flagsOf(node);
  }

  /** The flags of a node split into the ones for its fields (by key) and the ones for the node itself. */
  attributed(node: GraphNode, keys: readonly string[]): Attributed<NodeFlag> {
    return attribute(node.id, this.flagsFor(node), keys);
  }
}
