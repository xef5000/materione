/**
 * What a template may name at a node (logic.md section 2.6, 3, 31): the trigger's context schema, the declared
 * variables, the run-local and session variables the flow writes, the arguments a named flow is called with, the
 * expression functions and the placeholders. One list serves the autocomplete and the unknown-root lint, so what the
 * field offers is what the compiler accepts (D5).
 *
 * Pure of the DOM and of Svelte.
 */
import type { Graph, GraphNode } from './project';
import { readPayload } from './params';
import { isRecord, type LogicBlock } from './tree';
import type { DeclaredVariable } from './variables';
import { readPath } from './variables';
import type { ContextVar, FunctionEntry, PlaceholderEntry, Vocabulary } from './vocabulary';

export interface ScopeEntry {
  /** The dotted path an expression writes (`player.name`, `gui.args.*`). A trailing `.*` stands for a family. */
  path: string;
  type: string;
  description: string;
  origin: 'context' | 'variable' | 'local' | 'session' | 'args';
}

export interface TemplateScope {
  paths: ScopeEntry[];
  functions: FunctionEntry[];
  placeholders: PlaceholderEntry[];
  /** The first segments an expression may start with; anything else is the compiler's "unknown root". */
  roots: ReadonlySet<string>;
}

/** The expansions Materione's own PlaceholderAPI expansion defines (logic.md section 3.3). */
const OWN_PLACEHOLDERS: PlaceholderEntry[] = [
  { token: 'materione_gui_open', description: 'The id of the GUI the player has open' },
  { token: 'materione_gui_last', description: 'The id of the GUI the player had open last' },
];

export const EMPTY_SCOPE: TemplateScope = { paths: [], functions: [], placeholders: [], roots: new Set() };

function context(vocabulary: Vocabulary, trigger: string | undefined, host: string): ContextVar[] {
  if (!trigger) return [];
  const entry = vocabulary.triggers.get(trigger) ?? vocabulary.triggers.get(trigger.split(':')[0]!);
  return (entry?.context ?? []).filter((variable) => variable.hosts.length === 0 || variable.hosts.includes(host));
}

/** The trigger a rule listens to (`on:` as authored), or undefined for a flow or a rule without one. */
function triggerOfRule(block: LogicBlock, owner: string): string | undefined {
  if (!owner.startsWith('behavior.')) return undefined;
  const rule = block.behavior?.[Number(owner.slice('behavior.'.length))];
  return isRecord(rule) && typeof rule.on === 'string' ? rule.on : undefined;
}

/** The `with:` keys the calls of a flow pass, and the triggers of the rules that reach it. */
function callersOf(graph: Graph, block: LogicBlock, flow: string): { args: Set<string>; triggers: string[] } {
  const args = new Set<string>();
  const triggers: string[] = [];
  for (const node of graph.nodes) {
    if (node.kind !== 'call') continue;
    const value = node.value;
    const target = typeof value === 'string' ? value : isRecord(value) && typeof value.flow === 'string' ? value.flow : '';
    if (target !== flow) continue;
    const given = isRecord(value) && isRecord(value.with) ? value.with : isRecord(node.options.with) ? node.options.with : null;
    if (given) for (const key of Object.keys(given)) args.add(key);
    const on = triggerOfRule(block, node.owner);
    if (on) triggers.push(on);
  }
  return { args, triggers };
}

/** The run-local and session variable names the owner's flow writes (`set_var`, `prompt`), as expression paths. */
function written(graph: Graph, owner: string, vocabulary: Vocabulary): ScopeEntry[] {
  const out: ScopeEntry[] = [];
  const seen = new Set<string>();
  const note = (path: string, origin: 'local' | 'session', description: string): void => {
    if (seen.has(path)) return;
    seen.add(path);
    out.push({ path, type: 'raw', description, origin });
  };
  for (const node of graph.nodes) {
    if (node.owner !== owner || node.kind !== 'action' || (node.op !== 'set_var' && node.op !== 'prompt')) continue;
    const payload = readPayload(node.value, vocabulary.actions.get(node.op)?.shorthand);
    const name = typeof payload.var === 'string' ? payload.var : typeof payload.name === 'string' ? payload.name : '';
    if (!name || name.includes('${')) continue;
    if (name.startsWith('session.')) note(`vars.session.${name.slice('session.'.length)}`, 'session', 'A session variable of this GUI');
    else if (name.startsWith('local.')) note(`vars.${name.slice('local.'.length)}`, 'local', 'Written earlier in this run');
    else if (!/^(player|server)\./.test(name) && !name.includes(':')) note(`vars.${name}`, 'local', 'Written earlier in this run');
  }
  return out;
}

/** A layer of the GUI being edited, as far as the scope needs it: a list layer names the source its entries come from. */
export interface LayerLike {
  id: string;
  type: string;
  source?: unknown;
}

/** The name of the list source a list layer uses: `source: online_players`, `{type: ...}`, or `{pack_items: shop}`. */
function sourceName(source: unknown): string | null {
  if (typeof source === 'string') return source;
  if (!isRecord(source)) return null;
  if (typeof source.type === 'string') return source.type;
  if (Array.isArray(source.entries)) return 'static';
  const keys = Object.keys(source);
  return keys.length === 1 ? keys[0]! : null;
}

/** The scope of a template at a node. `host` is the resource kind being edited (`gui`); `layers` are its layers. */
export function scopeFor(
  graph: Graph,
  block: LogicBlock,
  node: GraphNode | null,
  vocabulary: Vocabulary | null,
  declared: readonly DeclaredVariable[],
  host = 'gui',
  layers: readonly LayerLike[] = [],
): TemplateScope {
  if (!vocabulary) return EMPTY_SCOPE;
  const paths: ScopeEntry[] = [];
  const add = (entry: ScopeEntry): void => {
    if (!paths.some((existing) => existing.path === entry.path)) paths.push(entry);
  };
  const owner = node?.owner ?? '';
  let triggers: (string | undefined)[] = [triggerOfRule(block, owner)];
  const args: ScopeEntry[] = [];
  if (owner.startsWith('flows.')) {
    const callers = callersOf(graph, block, owner.slice('flows.'.length));
    triggers = callers.triggers.length > 0 ? callers.triggers : [undefined];
    args.push({ path: 'args.*', type: 'raw', description: 'An argument this flow was called with', origin: 'args' });
    for (const key of callers.args) args.push({ path: `args.${key}`, type: 'raw', description: 'Passed by a call of this flow', origin: 'args' });
  }
  for (const trigger of triggers) {
    for (const variable of context(vocabulary, trigger, host)) {
      add({ path: variable.path, type: variable.type, description: variable.description, origin: 'context' });
    }
  }
  if (paths.length === 0) {
    // no trigger to read (a flow nothing calls yet, a rule without a trigger): the roots every trigger of the host shares
    const everyone = [...vocabulary.triggers.values()].filter((entry) => entry.firedBy.includes(host));
    const first = everyone[0];
    if (first) {
      for (const variable of first.context) {
        if (everyone.every((entry) => entry.context.some((other) => other.path === variable.path))) {
          add({ path: variable.path, type: variable.type, description: variable.description, origin: 'context' });
        }
      }
    }
  }
  args.forEach(add);
  // a rule about a list layer reads that list's entries: the entry fields its source declares (logic.md section 28, 31)
  const rule = owner.startsWith('behavior.') ? block.behavior?.[Number(owner.slice('behavior.'.length))] : undefined;
  const layerIds = isRecord(rule) ? (Array.isArray(rule.layer) ? rule.layer : [rule.layer]) : [];
  for (const layer of layers) {
    if (layer.type !== 'list' || !layerIds.includes(layer.id)) continue;
    const name = sourceName(layer.source);
    for (const variable of (name ? vocabulary.listSources.get(name)?.entry : undefined) ?? []) {
      if (!variable.path.endsWith('*')) add({ path: variable.path, type: variable.type, description: variable.description, origin: 'context' });
    }
  }
  for (const variable of declared) {
    add({
      path: readPath(variable),
      type: variable.type,
      description: variable.description || `${variable.scope} ${variable.type} variable, default ${String(variable.default)}`,
      origin: 'variable',
    });
  }
  if (owner) written(graph, owner, vocabulary).forEach(add);
  const roots = new Set<string>(['vars']);
  for (const entry of paths) roots.add(entry.path.split('.')[0]!);
  const placeholders = [
    ...OWN_PLACEHOLDERS,
    ...declared.map((variable) => ({
      token: `materione_var_${variable.pack}_${variable.key}`,
      description: `${variable.scope} variable ${variable.id}`,
    })),
    ...vocabulary.placeholders,
  ];
  return { paths, functions: vocabulary.functions, placeholders, roots };
}
