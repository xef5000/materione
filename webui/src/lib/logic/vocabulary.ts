/**
 * The behavior vocabulary as the Logic tab reads it (logic.md §4, §7.3, §8): the palette, the node
 * colours and the default shape of a new node all come from `logic.vocabulary`, never from a list in
 * the editor (D5). An action an add-on registers appears under its own category, an unavailable provider
 * greys its entries out with the server's reason, and a category the editor has no colour for still draws.
 *
 * The one fixed thing is the colour palette: logic.md §7.3 fixes eight category colours in the studio's
 * tokens, and `--logic-addon` is the colour of every category beyond them.
 *
 * Pure of the DOM.
 */
import type { IconName } from '../components/icons';
import { isRecord, type Json } from './tree';

/** One parameter of an action, a condition or a trigger filter, as the self-description lists it. */
export interface ParamInfo {
  id: string;
  type: string;
  required: boolean;
  default?: string;
  description?: string;
  label?: string;
  allowedValues?: string[];
}

export interface Availability {
  available: boolean;
  /** The server's reason an entry cannot run here (a provider that is not installed). */
  reason?: string;
}

export interface ActionEntry extends Availability {
  id: string;
  displayName: string;
  description: string;
  category: string;
  /** The parameter a bare scalar value fills (`sound: x` fills `sound`). */
  shorthand?: string;
  parameters: ParamInfo[];
  registeredBy?: string;
}

export interface ConditionEntry extends Availability {
  id: string;
  displayName: string;
  description: string;
  category: string;
  shorthand?: string;
  parameters: ParamInfo[];
}

export interface TriggerEntry {
  id: string;
  displayName: string;
  description: string;
  /** The hosts that fire it (`gui`, `item`, `block`, ...). */
  firedBy: string[];
  details: string[];
  filters: ParamInfo[];
}

export interface StepKindEntry {
  id: string;
  displayName: string;
  description: string;
  supported: boolean;
}

export interface CategoryEntry {
  id: string;
  displayName: string;
  builtIn: boolean;
}

export interface Vocabulary {
  version: number;
  actions: Map<string, ActionEntry>;
  conditions: Map<string, ConditionEntry>;
  triggers: Map<string, TriggerEntry>;
  steps: Map<string, StepKindEntry>;
  categories: CategoryEntry[];
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function params(value: unknown): ParamInfo[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord).map((entry) => ({
    id: text(entry.id),
    type: text(entry.type, 'string'),
    required: entry.required === true,
    default: typeof entry.default === 'string' ? entry.default : undefined,
    description: typeof entry.description === 'string' ? entry.description : undefined,
    label: typeof entry.label === 'string' ? entry.label : undefined,
    allowedValues: Array.isArray(entry.allowedValues) ? entry.allowedValues.filter((v): v is string => typeof v === 'string') : undefined,
  }));
}

function availability(entry: Json): Availability {
  return entry.available === false
    ? { available: false, reason: text(entry.unavailableReason, 'This is not available on this server.') }
    : { available: true };
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

/** Reads the `logic.vocabulary` reply; null when it is not a vocabulary at all. */
export function readVocabulary(raw: unknown): Vocabulary | null {
  if (!isRecord(raw) || !Array.isArray(raw.actions)) return null;
  const actions = new Map<string, ActionEntry>();
  for (const entry of raw.actions.filter(isRecord)) {
    const id = text(entry.id);
    if (!id) continue;
    actions.set(id, {
      id,
      displayName: text(entry.displayName, id),
      description: text(entry.description),
      category: text(entry.category, 'player'),
      shorthand: typeof entry.shorthand === 'string' ? entry.shorthand : undefined,
      parameters: params(entry.parameters),
      registeredBy: typeof entry.registeredBy === 'string' ? entry.registeredBy : undefined,
      ...availability(entry),
    });
  }
  const conditions = new Map<string, ConditionEntry>();
  for (const entry of Array.isArray(raw.conditions) ? raw.conditions.filter(isRecord) : []) {
    const id = text(entry.id);
    if (!id) continue;
    conditions.set(id, {
      id,
      displayName: text(entry.displayName, id),
      description: text(entry.description),
      category: text(entry.category, 'player'),
      shorthand: typeof entry.shorthand === 'string' ? entry.shorthand : undefined,
      parameters: params(entry.parameters),
      ...availability(entry),
    });
  }
  const triggers = new Map<string, TriggerEntry>();
  for (const entry of Array.isArray(raw.triggers) ? raw.triggers.filter(isRecord) : []) {
    const id = text(entry.id);
    if (!id) continue;
    triggers.set(id, {
      id,
      displayName: text(entry.displayName, id),
      description: text(entry.description),
      firedBy: strings(entry.firedBy),
      details: strings(entry.details),
      filters: params(entry.filters),
    });
  }
  const steps = new Map<string, StepKindEntry>();
  for (const entry of Array.isArray(raw.steps) ? raw.steps.filter(isRecord) : []) {
    const id = text(entry.id);
    if (!id) continue;
    steps.set(id, { id, displayName: text(entry.displayName, id), description: text(entry.description), supported: entry.supported !== false });
  }
  const categories = (Array.isArray(raw.categories) ? raw.categories.filter(isRecord) : []).map((entry) => ({
    id: text(entry.id),
    displayName: text(entry.displayName, text(entry.id)),
    builtIn: entry.builtIn === true,
  })).filter((entry) => entry.id);
  return { version: typeof raw.version === 'number' ? raw.version : 1, actions, conditions, triggers, steps, categories };
}

// ------------------------------------------------------------------ colour and icon by category

const COLORS: Record<string, string> = {
  trigger: 'var(--logic-trigger)',
  player: 'var(--logic-player)',
  gui: 'var(--logic-gui)',
  economy: 'var(--logic-economy)',
  data: 'var(--logic-data)',
  world: 'var(--logic-world)',
  server: 'var(--logic-server)',
  flow: 'var(--logic-flow)',
  integration: 'var(--logic-integration)',
};

/** The CSS colour of a category: the studio's token (logic.md §7.3), or the add-on colour for one it does not know. */
export function categoryColor(category: string): string {
  return COLORS[category] ?? 'var(--logic-addon)';
}

const CATEGORY_ICONS: Record<string, IconName> = {
  trigger: 'zap',
  player: 'user',
  gui: 'app-window',
  world: 'sprout',
  server: 'server',
  economy: 'coins',
  data: 'database',
  flow: 'git-branch',
  integration: 'plug',
};

export function categoryIcon(category: string): IconName {
  return CATEGORY_ICONS[category] ?? 'sparkles';
}

/** What a node kind is coloured as: a rule as `trigger`, a control step as `flow`, an action as its category. */
export function nodeCategory(vocabulary: Vocabulary | null, kind: 'trigger' | 'entry' | 'control' | 'action', op: string): string {
  if (kind === 'trigger') return 'trigger';
  if (kind === 'entry' || kind === 'control') return 'flow';
  return vocabulary?.actions.get(op)?.category ?? 'player';
}

// ------------------------------------------------------------------ default shapes

/** The value a new node's primary field starts with: the parameter's default, else something of its type. */
function starting(param: ParamInfo | undefined): unknown {
  if (!param) return '';
  if (param.default !== undefined && param.default !== '') {
    if (/^(integer|decimal)$/.test(param.type) && Number.isFinite(Number(param.default))) return Number(param.default);
    if (param.type === 'boolean') return param.default === 'true';
    return param.default;
  }
  if (/^(integer|decimal)$/.test(param.type)) return 1;
  if (param.type === 'boolean') return true;
  return '';
}

function shorthandParam(entry: { shorthand?: string; parameters: ParamInfo[] }): ParamInfo | undefined {
  return entry.shorthand ? entry.parameters.find((param) => param.id === entry.shorthand) : undefined;
}

/**
 * The step a new action node is born as. An action with a shorthand starts with that parameter's
 * starting value (`sound: ''`); one with no required parameter is a bare name (`- gui.close`); one with
 * required parameters but no shorthand starts as an empty mapping.
 */
export function defaultActionStep(entry: ActionEntry): unknown {
  if (entry.shorthand) return { [entry.id]: starting(shorthandParam(entry)) };
  if (!entry.parameters.some((param) => param.required)) return entry.id;
  return { [entry.id]: {} };
}

export function defaultConditionStep(entry: ConditionEntry): unknown {
  const value = entry.shorthand ? starting(shorthandParam(entry)) : {};
  return { if: { [entry.id]: value }, then: [] };
}

/** The new rule for a trigger. */
export function defaultRule(entry: TriggerEntry): Json {
  return { on: entry.id, do: [] };
}

/** The default step for a control kind. `flow` is the first named flow, for `call`. */
export function defaultControlStep(kind: string, flow = ''): unknown {
  switch (kind) {
    case 'if':
      return { if: '${true}', then: [] };
    case 'require':
      return { require: [] };
    case 'choose':
      return { choose: [{ if: '${true}', then: [] }] };
    case 'wait':
      return { wait: 20 };
    case 'stop':
      return 'stop';
    case 'call':
      return { call: flow };
    default:
      return kind;
  }
}

// ------------------------------------------------------------------ the palette

export type PaletteKind = 'trigger' | 'action' | 'condition' | 'control';

export interface PaletteEntry {
  key: string;
  kind: PaletteKind;
  /** The action, condition, trigger or control kind id. */
  id: string;
  title: string;
  description: string;
  category: string;
  available: boolean;
  reason?: string;
  /** Extra words the search matches (the id and the registering add-on). */
  keywords: string;
}

export interface PaletteGroup {
  id: string;
  title: string;
  category: string;
  entries: PaletteEntry[];
}

export interface PaletteOptions {
  /** The host of the resource being edited (`gui`): only the triggers that host fires are listed. */
  host?: string;
}

/**
 * The palette, grouped as logic.md §7.3 says: Triggers, then the action categories in the server's order,
 * then Conditions, then Flow. Every entry comes from the vocabulary; an unavailable one is listed with its
 * reason so the author sees what an add-on or a provider would unlock.
 */
export function paletteGroups(vocabulary: Vocabulary, options: PaletteOptions = {}): PaletteGroup[] {
  const groups: PaletteGroup[] = [];
  const hosted = [...vocabulary.triggers.values()].filter((entry) => !options.host || entry.firedBy.includes(options.host));
  const triggers = hosted.length > 0 ? hosted : [...vocabulary.triggers.values()];
  if (triggers.length > 0) {
    groups.push({
      id: 'triggers',
      title: 'Triggers',
      category: 'trigger',
      entries: triggers.map((entry) => ({
        key: `trigger:${entry.id}`,
        kind: 'trigger',
        id: entry.id,
        title: entry.displayName,
        description: entry.description,
        category: 'trigger',
        available: true,
        keywords: `${entry.id} ${entry.displayName} trigger rule event`,
      })),
    });
  }
  const order = vocabulary.categories.map((category) => category.id);
  for (const action of vocabulary.actions.values()) if (!order.includes(action.category)) order.push(action.category);
  const control = [...vocabulary.steps.values()].filter((step) => step.id !== 'action');
  for (const category of order) {
    const actions = [...vocabulary.actions.values()].filter((action) => action.category === category);
    const steps = category === 'flow' ? control : [];
    if (actions.length === 0 && steps.length === 0) continue;
    const label = vocabulary.categories.find((entry) => entry.id === category)?.displayName ?? category;
    groups.push({
      id: category,
      title: label,
      category,
      entries: [
        ...steps.map((step): PaletteEntry => ({
          key: `control:${step.id}`,
          kind: 'control',
          id: step.id,
          title: step.displayName,
          description: step.description,
          category: 'flow',
          available: step.supported,
          reason: step.supported ? undefined : 'The running engine does not execute this step yet.',
          keywords: `${step.id} ${step.displayName} flow control`,
        })),
        ...actions.map((action): PaletteEntry => ({
          key: `action:${action.id}`,
          kind: 'action',
          id: action.id,
          title: action.displayName,
          description: action.description,
          category: action.category,
          available: action.available,
          reason: action.reason,
          keywords: `${action.id} ${action.displayName} ${action.registeredBy ?? ''}`,
        })),
      ],
    });
  }
  const conditions = [...vocabulary.conditions.values()];
  if (conditions.length > 0) {
    groups.push({
      id: 'conditions',
      title: 'Conditions',
      category: 'flow',
      entries: conditions.map((condition) => ({
        key: `condition:${condition.id}`,
        kind: 'condition',
        id: condition.id,
        title: condition.displayName,
        description: condition.description,
        category: condition.category,
        available: condition.available,
        reason: condition.reason,
        keywords: `${condition.id} ${condition.displayName} condition if`,
      })),
    });
  }
  // the Flow group comes last, after Conditions, as §7.3 groups it
  const flow = groups.findIndex((group) => group.id === 'flow');
  if (flow >= 0) groups.push(...groups.splice(flow, 1));
  return groups;
}

/** The entries whose title, id, description or keywords contain every word of `query`; all of them when it is blank. */
export function searchPalette(groups: readonly PaletteGroup[], query: string): PaletteGroup[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return groups.map((group) => ({ ...group }));
  return groups
    .map((group) => ({
      ...group,
      entries: group.entries.filter((entry) => {
        const haystack = `${entry.title} ${entry.keywords} ${entry.description} ${group.title}`.toLowerCase();
        return words.every((word) => haystack.includes(word));
      }),
    }))
    .filter((group) => group.entries.length > 0);
}

/** The step a palette entry inserts, or the rule for a trigger. `flow` is the first named flow, for `call`. */
export function stepFor(vocabulary: Vocabulary, entry: PaletteEntry, flow = ''): unknown {
  if (entry.kind === 'control') return defaultControlStep(entry.id, flow);
  if (entry.kind === 'condition') return defaultConditionStep(vocabulary.conditions.get(entry.id)!);
  if (entry.kind === 'action') return defaultActionStep(vocabulary.actions.get(entry.id)!);
  throw new Error('a trigger is a rule, not a step');
}
