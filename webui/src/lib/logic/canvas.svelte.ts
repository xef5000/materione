/**
 * The Logic tab's reactive core: the graph the shared document projects to, the layout, the camera, the
 * selection and the palette, and the gestures that become history commands. The rules are in the pure
 * modules beside it (tree, project, layout, plan, command); this class only wires them to Svelte runes
 * and to the GUI studio, whose history, dirty state and save it shares (logic.md §7.1: no editor-only
 * model, and one undo stack across the tabs).
 */
import type { RpcChannel } from '../api';
import type { GuiStudioState } from '../gui/studio.svelte';
import { buildCommand } from './command';
import { layout, wires, type Layout, type Point, type Rect, type Wire } from './layout';
import {
  defaultSlot, planAddCase, planAddFlow, planAddRule, planConnect, planInsert, planMoveChain, planPosition,
  planRemove, planRemoveCase, planSetValue, refuse, tipOf, type NameOf, type Plan, type Slot,
} from './plan';
import { project, type Graph, type GraphNode } from './project';
import { logicApiAvailable, logicValidate, logicVocabulary, type LogicDiagnostic } from './server';
import { blockOf, blockProblems, type LogicBlock } from './tree';
import {
  categoryColor, defaultRule, nodeCategory, paletteGroups, readVocabulary, searchPalette, stepFor,
  type PaletteEntry, type PaletteGroup, type Vocabulary,
} from './vocabulary';
import { frameView, revealRect, stepZoom, wheelZoom, zoomAt, type Size, type View } from './viewport';

/** How long editing must pause before the compiler is asked about the draft. */
const CHECK_PAUSE_MS = 400;
/** A refusal stays in the status line this long. */
const SAY_MS = 7000;

export interface NodeFlag {
  severity: 'error' | 'warn' | 'info';
  message: string;
}

export interface PaletteState {
  /** Where the entry goes; null when there is no rule yet to put a step in. */
  slot: Slot | null;
  /** Said in the palette header: "Insert after Play sound". */
  label: string;
}

export class LogicCanvasState {
  readonly gui: GuiStudioState;
  /** The kind of resource being edited (`gui`): the palette lists the triggers that kind fires. */
  readonly host: string;
  #sayTimer: ReturnType<typeof setTimeout> | undefined;
  #checkTimer: ReturnType<typeof setTimeout> | undefined;
  #checkSerial = 0;

  vocabulary = $state.raw<Vocabulary | null>(null);
  /** `unavailable` when the server does not advertise the logic capability (an older plugin, or the demo). */
  status = $state<'off' | 'unavailable' | 'loading' | 'ready' | 'failed'>('off');
  error = $state('');

  // The derived views are assigned in the constructor: they read `gui` and `host`, which it sets first.
  block: LogicBlock;
  /** Why the logic cannot be drawn faithfully; the canvas then refuses to edit it. */
  problems: string[];
  graph: Graph;
  /** Positions in flight (a node being dragged); they are not in the history until the pointer lets go. */
  dragging = $state.raw<ReadonlyMap<string, Point>>(new Map());
  placed: Layout;
  routes: Wire[];

  view = $state<View>({ zoom: 1, panX: 32, panY: 32 });
  stage = $state<Size>({ w: 0, h: 0 });
  /** The camera is still the framed one: a window resize reframes it. */
  autoFit = $state(true);
  /** Animate the next camera change (a frame or a reveal), not a drag. */
  glide = $state(false);

  selection = $state<string | null>(null);
  palette = $state<PaletteState | null>(null);
  /** One line for the status bar: why a gesture did nothing. */
  message = $state('');
  messageKind = $state<'refusal' | 'hint'>('hint');
  /** The compiler's verdict on the draft, by node. */
  verdicts = $state.raw<ReadonlyMap<string, NodeFlag[]>>(new Map());
  checking = $state(false);

  editable: boolean;
  selected: GraphNode | null;
  groups: PaletteGroup[];

  constructor(gui: GuiStudioState, host = 'gui') {
    this.gui = gui;
    this.host = host;
    this.block = $derived(blockOf(this.gui.doc.rest));
    this.problems = $derived(blockProblems(this.gui.doc.rest));
    this.graph = $derived(project(this.block));
    this.placed = $derived(layout(this.graph, this.dragging));
    this.routes = $derived(wires(this.graph, this.placed.rects));
    this.editable = $derived(!this.gui.readOnly && this.problems.length === 0 && this.status === 'ready');
    this.selected = $derived(this.selection ? this.graph.byId.get(this.selection) ?? null : null);
    this.groups = $derived(this.vocabulary ? paletteGroups(this.vocabulary, { host: this.host }) : []);
  }

  // ------------------------------------------------------------------ names

  /** The title a node wears, and what messages call it. */
  nameOf: NameOf = (node) => {
    const vocabulary = this.vocabulary;
    if (node.kind === 'trigger') {
      const parts = node.op.split(':');
      const entry = vocabulary?.triggers.get(node.op) ?? vocabulary?.triggers.get(parts[0]!) ?? vocabulary?.triggers.get(parts.slice(0, 2).join(':'));
      return entry?.displayName ?? (node.op || 'Rule');
    }
    if (node.kind === 'entry') return `Flow ${node.op}`;
    if (node.kind === 'action') return vocabulary?.actions.get(node.op)?.displayName ?? (node.op || 'Step');
    return vocabulary?.steps.get(node.kind)?.displayName ?? node.kind.charAt(0).toUpperCase() + node.kind.slice(1);
  };

  /** The registry category a node is coloured by: a rule is a trigger, a control step is flow, an action is its category. */
  categoryOf(node: GraphNode): string {
    const kind = node.kind === 'trigger' ? 'trigger' : node.kind === 'entry' ? 'entry' : node.kind === 'action' ? 'action' : 'control';
    return nodeCategory(this.vocabulary, kind, node.op);
  }

  colorOf(node: GraphNode): string {
    return categoryColor(this.categoryOf(node));
  }

  /** What the compiler and the vocabulary say about a node. */
  flagsOf(node: GraphNode): NodeFlag[] {
    const out: NodeFlag[] = [...(this.verdicts.get(node.id) ?? [])];
    const entry = node.kind === 'action' ? this.vocabulary?.actions.get(node.op) : undefined;
    if (node.kind === 'action' && this.vocabulary) {
      if (!entry) out.push({ severity: 'warn', message: `No action called '${node.op}' is registered on this server.` });
      else if (!entry.available) out.push({ severity: 'warn', message: entry.reason ?? 'Not available on this server.' });
      else if (entry.shorthand && node.authored.own && node.authored.own[node.op] === '') {
        out.push({ severity: 'info', message: `Needs a ${entry.shorthand}.` });
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ connection

  /** Reads the vocabulary once a channel is there; the server decides what the palette holds. */
  async connect(channel: RpcChannel | null): Promise<void> {
    if (!channel) {
      this.status = 'off';
      return;
    }
    if (!logicApiAvailable(channel)) {
      this.status = 'unavailable';
      return;
    }
    if (this.status === 'ready' || this.status === 'loading') return;
    this.status = 'loading';
    try {
      const vocabulary = readVocabulary(await logicVocabulary(channel));
      if (!vocabulary) throw new Error('The server did not describe its actions.');
      this.vocabulary = vocabulary;
      this.status = 'ready';
      this.error = '';
    } catch (error) {
      this.status = 'failed';
      this.error = error instanceof Error ? error.message : String(error);
    }
  }

  /** Asks the compiler about the draft once editing pauses; its problems are drawn on the nodes (§7.4). */
  scheduleCheck(channel: RpcChannel | null): void {
    clearTimeout(this.#checkTimer);
    if (!channel || this.status !== 'ready' || this.problems.length > 0) return;
    this.#checkTimer = setTimeout(() => void this.#check(channel), CHECK_PAUSE_MS);
  }

  async #check(channel: RpcChannel): Promise<void> {
    const serial = ++this.#checkSerial;
    this.checking = true;
    try {
      const diagnostics = await logicValidate(channel, {
        pack: this.gui.pack,
        resource: this.gui.rootId,
        behavior: this.block.behavior ?? [],
        flows: this.block.flows ?? {},
      });
      if (serial === this.#checkSerial) this.verdicts = verdictsOf(this.graph, diagnostics);
    } catch {
      if (serial === this.#checkSerial) this.verdicts = new Map();
    } finally {
      if (serial === this.#checkSerial) this.checking = false;
    }
  }

  // ------------------------------------------------------------------ messages

  say(text: string, kind: 'refusal' | 'hint' = 'refusal'): void {
    clearTimeout(this.#sayTimer);
    this.message = text;
    this.messageKind = kind;
    if (text) this.#sayTimer = setTimeout(() => (this.message = ''), SAY_MS);
  }

  // ------------------------------------------------------------------ selection and camera

  select(id: string | null): void {
    this.selection = id && this.graph.byId.has(id) ? id : null;
  }

  /** Drops a selection that no longer exists (after an undo, say). */
  settle(): void {
    if (this.selection && !this.graph.byId.has(this.selection)) this.selection = null;
  }

  setView(view: View): void {
    this.view = view;
    this.autoFit = false;
  }

  /** Frames the selected node, else the whole graph (`F`). */
  frame(): void {
    const rect: Rect | undefined = this.selection ? this.placed.rects.get(this.selection) : undefined;
    this.glide = true;
    this.view = frameView(this.stage, rect ?? this.placed.bounds);
    this.autoFit = !rect;
  }

  /** The whole graph at the biggest size that fits (`0`). */
  fit(): void {
    this.glide = true;
    this.view = frameView(this.stage, this.placed.bounds);
    this.autoFit = true;
  }

  actualSize(): void {
    this.glide = true;
    this.setView(zoomAt(this.view, 1, { x: this.stage.w / 2, y: this.stage.h / 2 }));
  }

  zoomStep(direction: 1 | -1, anchor?: Point): void {
    this.glide = false;
    this.setView(stepZoom(this.view, direction, anchor ?? { x: this.stage.w / 2, y: this.stage.h / 2 }));
  }

  wheel(delta: number, anchor: Point): void {
    this.glide = false;
    this.setView(wheelZoom(this.view, delta, anchor));
  }

  pan(dx: number, dy: number): void {
    this.glide = false;
    this.setView({ ...this.view, panX: this.view.panX + dx, panY: this.view.panY + dy });
  }

  /** Brings a node into view if it is out of it. */
  reveal(id: string | null): void {
    const rect = id ? this.placed.rects.get(id) : undefined;
    if (!rect || this.stage.w === 0) return;
    const next = revealRect(this.view, this.stage, rect);
    if (next !== this.view) {
      this.glide = true;
      this.setView(next);
    }
  }

  /** Moves the selection along the graph: right follows the sequence, left goes back, up and down walk the nodes in order. */
  navigate(direction: 'left' | 'right' | 'up' | 'down'): void {
    const graph = this.graph;
    const current = this.selection ? graph.byId.get(this.selection) : undefined;
    if (!current) {
      this.select(graph.nodes[0]?.id ?? null);
      this.reveal(this.selection);
      return;
    }
    let next: string | undefined;
    if (direction === 'right') {
      for (const port of current.ports) {
        next = graph.leaving.get(`${current.id}|${port.id}`)?.to;
        if (next) break;
      }
    } else if (direction === 'left') {
      next = graph.feeder.get(current.id)?.from;
    } else {
      const index = graph.nodes.findIndex((node) => node.id === current.id);
      next = graph.nodes[index + (direction === 'down' ? 1 : -1)]?.id;
    }
    if (next) {
      this.select(next);
      this.reveal(next);
    }
  }

  // ------------------------------------------------------------------ commands

  /** Runs an accepted plan as one history command; says why when it is refused. */
  run(plan: Plan): boolean {
    if (!this.editable) {
      this.say(this.problems[0] ? `This logic cannot be edited here: ${this.problems[0]}.` : 'This file is read-only here.');
      return false;
    }
    const built = buildCommand(this.gui.doc, plan);
    if (!built.ok) {
      this.say(built.reason);
      return false;
    }
    if (built.command) this.gui.run(built.command);
    this.message = '';
    if (plan.ok && plan.select !== undefined) {
      this.selection = plan.select;
      this.settle();
      this.reveal(this.selection);
    }
    return true;
  }

  undo(): void {
    this.gui.undo();
    this.settle();
  }

  redo(): void {
    this.gui.redo();
    this.settle();
  }

  remove(id: string | null = this.selection): void {
    if (!id) return;
    this.run(planRemove(this.graph, id, this.nameOf));
  }

  setValue(id: string, text: string): void {
    const node = this.graph.byId.get(id);
    if (!node) return;
    const was = node.kind === 'action' ? node.value : node.kind === 'trigger' ? node.op : node.value;
    const numeric = typeof was === 'number' && /^-?\d+(\.\d+)?$/.test(text.trim());
    const value = numeric ? Number(text.trim()) : text;
    if (value === was) return;
    this.run(planSetValue(this.graph, id, value, this.nameOf));
  }

  /** The node was dragged: the position is the only thing that changes, so it is the only thing written. */
  place(id: string, point: Point): void {
    const at = this.placed.rects.get(id);
    if (at && at.x === point.x && at.y === point.y) return;
    this.run(planPosition(this.graph, id, point.x, point.y));
  }

  /** A wire dragged from an output port onto a node. */
  connectTo(from: { node: string; port: string }, to: string): boolean {
    return this.run(planConnect(this.graph, this.block, from, to, this.nameOf));
  }

  /** A chain lifted by its input and dropped on an output port. */
  moveChain(id: string, onto: { node: string; port: string }): boolean {
    const target = this.graph.byId.get(onto.node);
    if (!target) return false;
    const slot = target.ports.find((port) => port.id === onto.port);
    if (!slot) return this.run(refuse('That is not an output port.'));
    const where = this.slotFor(onto.node, onto.port);
    return this.run(where ? planMoveChain(this.graph, this.block, id, where, this.nameOf) : refuse('That is not an output port.'));
  }

  slotFor(node: string, port: string): Slot | null {
    const info = this.graph.byId.get(node);
    const entry = info?.ports.find((candidate) => candidate.id === port);
    if (!info || !entry) return null;
    return entry.sibling
      ? { owner: node, port, list: entry.list, index: (info.index ?? 0) + 1, exists: true }
      : { owner: node, port, list: entry.list, index: 0, exists: entry.exists };
  }

  addCase(id: string): void {
    this.run(planAddCase(this.graph, this.block, id, this.nameOf));
  }

  removeCase(id: string): void {
    this.run(planRemoveCase(this.graph, this.block, id, this.nameOf));
  }

  addFlow(): void {
    this.run(planAddFlow(this.block));
  }

  // ------------------------------------------------------------------ the palette

  /** The slot "add a node" fills when nothing says otherwise: after the selection, else the end of the last rule. */
  defaultTarget(): PaletteState {
    const graph = this.graph;
    const selected = this.selection ? graph.byId.get(this.selection) : undefined;
    if (selected) {
      const slot = defaultSlot(graph, selected.id);
      if (slot) return { slot, label: this.describeSlot(slot) };
    }
    const lanes = graph.nodes.filter((node) => node.kind === 'trigger' || node.kind === 'entry');
    const last = lanes[lanes.length - 1];
    const slot = last ? tipOf(graph, last.id) : null;
    return { slot, label: slot ? this.describeSlot(slot) : 'Start with a trigger' };
  }

  describeSlot(slot: Slot): string {
    const owner = this.graph.byId.get(slot.owner);
    if (!owner) return 'Insert';
    const port = owner.ports.find((entry) => entry.id === slot.port);
    if (slot.port === 'after') return `Insert after ${this.nameOf(owner)}`;
    return `Insert into ${this.nameOf(owner)}${port && owner.ports.length > 1 ? ` · ${port.label}` : ''}`;
  }

  openPalette(target?: Slot): void {
    if (!this.editable) {
      this.say(this.status === 'ready' ? 'This file is read-only here.' : 'The palette needs the server\'s vocabulary.');
      return;
    }
    this.palette = target ? { slot: target, label: this.describeSlot(target) } : this.defaultTarget();
  }

  closePalette(): void {
    this.palette = null;
  }

  search(query: string): PaletteGroup[] {
    return searchPalette(this.groups, query);
  }

  /** Puts a palette entry on the canvas: a trigger as a new rule, anything else at the palette's slot. */
  pick(entry: PaletteEntry): boolean {
    const vocabulary = this.vocabulary;
    const target = this.palette;
    if (!vocabulary) return false;
    if (!entry.available) {
      this.say(entry.reason ?? `${entry.title} is not available on this server.`);
      return false;
    }
    let plan: Plan;
    if (entry.kind === 'trigger') {
      const trigger = vocabulary.triggers.get(entry.id);
      plan = trigger ? planAddRule(this.block, defaultRule(trigger), `Add ${entry.title}`) : refuse('Unknown trigger.');
    } else if (!target?.slot) {
      plan = refuse('Add a trigger first: a step needs a rule to run in.');
    } else {
      const flow = Object.keys(this.block.flows ?? {})[0] ?? '';
      plan = planInsert(this.graph, this.block, target.slot, stepFor(vocabulary, entry, flow), `Add ${entry.title}`);
    }
    const done = this.run(plan);
    if (done) this.palette = null;
    return done;
  }
}

/** Maps the compiler's step-path diagnostics onto nodes: the deepest node whose path is a prefix of the diagnostic's. */
export function verdictsOf(graph: Graph, diagnostics: readonly LogicDiagnostic[]): Map<string, NodeFlag[]> {
  const out = new Map<string, NodeFlag[]>();
  for (const diagnostic of diagnostics) {
    const path = (diagnostic.field ?? []).join('.');
    let best: string | null = null;
    for (const node of graph.nodes) {
      if ((path === node.id || path.startsWith(`${node.id}.`)) && (best === null || node.id.length > best.length)) best = node.id;
    }
    if (!best) continue;
    const severity = diagnostic.severity === 'error' || diagnostic.severity === 'info' ? diagnostic.severity : 'warn';
    out.set(best, [...(out.get(best) ?? []), { severity, message: `${diagnostic.code}: ${diagnostic.message}` }]);
  }
  return out;
}
