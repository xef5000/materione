/**
 * Behaviour authoring over the existing formats (D4): an item's `mechanics:`
 * list and any resource's `behavior:` rules. Everything here reads and
 * writes those same YAML shapes — no second rule language — and everything
 * it explains comes from the server's self-description (`authoring.describe`).
 * Unknown keys, unregistered mechanics and unknown actions are preserved and
 * shown, never dropped.
 */
import type { TypeParam } from './api';

export interface Described {
  displayName?: string;
  description?: string;
  /** One sentence saying when it acts in game; absent when the entry never declared it. */
  activation?: string;
  sinceVersion?: string;
  parameters?: TypeParam[];
}

export interface BehaviorEventInfo {
  id: string;
  displayName: string;
  description: string;
  details: string[];
  /** Content type names whose runtime fires this event; empty when nothing does yet. */
  firedBy: string[];
}

export interface BehaviorActionInfo extends Described {
  id: string;
  shorthand?: string;
  deferred?: boolean;
}

export interface MechanicInfo extends Described {
  id: string;
  displayName: string;
  description: string;
  repeatable: boolean;
  registeredBy: string;
}

export interface BehaviorVocabulary {
  events: BehaviorEventInfo[];
  actions: BehaviorActionInfo[];
  ruleOptions: TypeParam[];
  actionOptions: TypeParam[];
  mechanics: MechanicInfo[];
}

export type Json = Record<string, unknown>;

const BUILT_IN_NAMESPACE = 'materione';
const ACTION_PROPERTY_KEYS = ['chance', 'delay'];

function record(value: unknown): Json | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Json : null;
}

function clone<T>(value: T): T {
  return value === undefined ? value : JSON.parse(JSON.stringify(value)) as T;
}

function own(target: Json, key: string, value: unknown): void {
  Object.defineProperty(target, key, { value, writable: true, enumerable: true, configurable: true });
}

/** A param value the author cleared: absent from YAML rather than written empty. */
export function isBlank(value: unknown): boolean {
  return value === undefined || value === null || value === '' ||
    (Array.isArray(value) && value.length === 0) ||
    (typeof value === 'number' && Number.isNaN(value));
}

// ------------------------------------------------------------------ mechanics

/** Unqualified names alias only `materione:` (mechanics.md). */
export function fullMechanicId(name: string): string {
  return name.includes(':') ? name : `${BUILT_IN_NAMESPACE}:${name}`;
}

/** The name an item writes: short for built-ins, qualified for addons. */
export function authoredMechanicName(id: string): string {
  return id.startsWith(`${BUILT_IN_NAMESPACE}:`) ? id.slice(BUILT_IN_NAMESPACE.length + 1) : id;
}

export interface MechanicEntry {
  index: number;
  /** As authored; empty when the entry has no recognisable name. */
  name: string;
  params: Json;
  /** `- soulbound` (a bare name) vs `- soulbound: {...}`. */
  form: 'map' | 'name' | 'invalid';
}

export function mechanicEntries(value: unknown): MechanicEntry[] {
  if (!Array.isArray(value)) return [];
  return value.map((entry, index) => {
    if (typeof entry === 'string' && entry.trim()) {
      return { index, name: entry.trim(), params: {}, form: 'name' as const };
    }
    const map = record(entry);
    const keys = map ? Object.keys(map) : [];
    if (map && keys.length === 1) {
      return { index, name: keys[0]!, params: record(map[keys[0]!]) ?? {}, form: 'map' as const };
    }
    return { index, name: '', params: {}, form: 'invalid' as const };
  });
}

export function mechanicInfo(vocabulary: BehaviorVocabulary | null, name: string): MechanicInfo | undefined {
  const id = fullMechanicId(name);
  return vocabulary?.mechanics.find((mechanic) => mechanic.id === id);
}

/** Sets (or, when blank, removes) one parameter of one mechanic entry. Other entries are untouched. */
export function setMechanicParam(value: unknown, index: number, key: string, next: unknown): unknown[] {
  const list = Array.isArray(value) ? clone(value) : [];
  const entry = mechanicEntries(list)[index];
  if (!entry || entry.form === 'invalid') throw new Error('This ability entry cannot be edited here.');
  const params = clone(entry.params);
  if (isBlank(next)) delete params[key];
  else own(params, key, clone(next));
  const replaced: Json = {};
  own(replaced, entry.name, params);
  list[index] = replaced;
  return list;
}

export function canAddMechanic(value: unknown, mechanic: MechanicInfo): boolean {
  return mechanic.repeatable ||
    !mechanicEntries(value).some((entry) => entry.name && fullMechanicId(entry.name) === mechanic.id);
}

export function addMechanic(value: unknown, mechanic: MechanicInfo): unknown[] {
  if (!canAddMechanic(value, mechanic)) {
    throw new Error(`${mechanic.displayName} can only be added once.`);
  }
  const entry: Json = {};
  own(entry, authoredMechanicName(mechanic.id), {});
  return [...(Array.isArray(value) ? clone(value) : []), entry];
}

export function removeAt(value: unknown, index: number): unknown[] {
  const list = Array.isArray(value) ? clone(value) : [];
  list.splice(index, 1);
  return list;
}

// ---------------------------------------------------------------------- rules

export interface Selector {
  type: string;
  detail: string;
}

export function parseSelector(on: unknown): Selector {
  const text = typeof on === 'string' ? on : '';
  const colon = text.indexOf(':');
  return colon < 0
    ? { type: text.trim().toLowerCase(), detail: '' }
    : { type: text.slice(0, colon).trim().toLowerCase(), detail: text.slice(colon + 1).trim() };
}

export function formatSelector(selector: Selector): string {
  return selector.detail ? `${selector.type}:${selector.detail}` : selector.type;
}

export function eventInfo(vocabulary: BehaviorVocabulary | null, type: string): BehaviorEventInfo | undefined {
  return vocabulary?.events.find((event) => event.id === type);
}

/** Events the given content type's runtime actually fires. */
export function eventsFor(vocabulary: BehaviorVocabulary | null, host: string): BehaviorEventInfo[] {
  return (vocabulary?.events ?? []).filter((event) => event.firedBy.includes(host));
}

export function conditionsOf(rule: Json): string[] {
  const raw = rule.if;
  const list = Array.isArray(raw) ? raw : raw === undefined || raw === null ? [] : [raw];
  return list.map((entry) => String(entry));
}

export function withConditions(rule: Json, lines: string[]): Json {
  const next = clone(rule);
  const kept = lines.map((line) => line.trim()).filter(Boolean);
  if (!kept.length) delete next.if;
  else own(next, 'if', typeof rule.if === 'string' && kept.length === 1 ? kept[0] : kept);
  return next;
}

export interface ActionView {
  type: string;
  /** Parameters, with a scalar shorthand expanded to its named parameter. */
  params: Json;
  /** chance / delay, kept beside the type key. */
  options: Json;
  /** The authored payload was a scalar (`message: "Hi"`). */
  scalar: boolean;
  valid: boolean;
}

/** Mirrors RuleParser.parseAction: one non-property key is the type. */
export function actionView(entry: unknown, vocabulary: BehaviorVocabulary | null): ActionView {
  const map = record(entry);
  if (!map) return { type: '', params: {}, options: {}, scalar: false, valid: false };
  const options: Json = {};
  const types = Object.keys(map).filter((key) => {
    if (ACTION_PROPERTY_KEYS.includes(key)) {
      own(options, key, map[key]);
      return false;
    }
    return true;
  });
  if (types.length !== 1) return { type: types[0] ?? '', params: {}, options, scalar: false, valid: false };
  const type = types[0]!;
  const payload = map[type];
  const nested = record(payload);
  if (nested) return { type, params: clone(nested), options, scalar: false, valid: true };
  const params: Json = {};
  if (payload !== undefined && payload !== null) {
    const shorthand = actionInfo(vocabulary, type)?.shorthand ?? 'value';
    own(params, shorthand, payload);
  }
  return { type, params, options, scalar: payload !== undefined && payload !== null, valid: true };
}

export function actionInfo(vocabulary: BehaviorVocabulary | null, type: string): BehaviorActionInfo | undefined {
  return vocabulary?.actions.find((action) => action.id === type);
}

/** Writes an action back, keeping the scalar shorthand when only the shorthand parameter is set. */
export function writeAction(view: ActionView, vocabulary: BehaviorVocabulary | null): Json {
  const out: Json = {};
  const shorthand = actionInfo(vocabulary, view.type)?.shorthand;
  const keys = Object.keys(view.params);
  if (shorthand && keys.length === 1 && keys[0] === shorthand &&
    (view.scalar || record(view.params[shorthand]) === null) && !Array.isArray(view.params[shorthand])) {
    own(out, view.type, clone(view.params[shorthand]));
  } else {
    own(out, view.type, clone(view.params));
  }
  for (const [key, value] of Object.entries(view.options)) own(out, key, clone(value));
  return out;
}

export function actionsOf(rule: Json): unknown[] {
  return Array.isArray(rule.do) ? rule.do : rule.do === undefined ? [] : [rule.do];
}

export function setRuleKey(rule: Json, key: string, value: unknown): Json {
  const next = clone(rule);
  if (isBlank(value)) delete next[key];
  else own(next, key, clone(value));
  return next;
}

/** Replaces rule `index` in the list; every other rule keeps its exact value. */
export function replaceAt(value: unknown, index: number, next: unknown): unknown[] {
  const list = Array.isArray(value) ? clone(value) : [];
  list[index] = clone(next);
  return list;
}

function quote(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text.length > 40 ? `“${text.slice(0, 39)}…”` : `“${text}”`;
}

function actionPhrase(entry: unknown, vocabulary: BehaviorVocabulary | null): string {
  const view = actionView(entry, vocabulary);
  if (!view.valid) return 'an action this editor cannot read';
  const info = actionInfo(vocabulary, view.type);
  const name = (info?.displayName ?? view.type).toLowerCase();
  const main = info?.shorthand ? view.params[info.shorthand] : Object.values(view.params)[0];
  const parts = [main === undefined || record(main) ? name : `${name} ${quote(main)}`];
  if (typeof view.options.delay === 'number' && view.options.delay > 0) parts.push(`after ${view.options.delay} ticks`);
  if (typeof view.options.chance === 'number' && view.options.chance < 1) {
    parts.push(`${Math.round(view.options.chance * 100)}% of the time`);
  }
  return parts.join(' ');
}

/** "On right click, if player.health > 0: send message “Hi” (at most every 3 s)". */
export function ruleSentence(rule: Json, vocabulary: BehaviorVocabulary | null): string {
  const selector = parseSelector(rule.on);
  const event = eventInfo(vocabulary, selector.type);
  const trigger = `${selector.detail ? `${selector.detail} ` : ''}${(event?.displayName ?? (selector.type || 'no event')).toLowerCase()}`;
  const conditions = conditionsOf(rule);
  const actions = actionsOf(rule).map((entry) => actionPhrase(entry, vocabulary));
  const limits: string[] = [];
  if (typeof rule.cooldown === 'number' && rule.cooldown > 0) limits.push(`at most every ${formatMillis(rule.cooldown)}`);
  if (typeof rule.charges === 'number' && rule.charges > 0) {
    limits.push(`${rule.charges} ${rule.charges === 1 ? 'time' : 'times'} in total${rule.per === 'global' ? ' server-wide' : ''}`);
  }
  return `On ${trigger}${conditions.length ? `, if ${conditions.join(' and ')}` : ''}: ` +
    `${actions.length ? actions.join(', then ') : 'nothing yet'}` +
    `${limits.length ? ` (${limits.join(', ')})` : ''}`;
}

function formatMillis(ms: number): string {
  return ms % 1000 === 0 ? `${ms / 1000} s` : `${ms} ms`;
}

/**
 * Why (or whether) a rule runs on this content type, in the server's words.
 * `fires: false` means nothing on this server fires the event for the host,
 * so the rule can never run as written.
 */
export function activationOf(
  rule: Json,
  vocabulary: BehaviorVocabulary | null,
  host: string,
): { fires: boolean; text: string } {
  const selector = parseSelector(rule.on);
  const event = eventInfo(vocabulary, selector.type);
  if (!selector.type) return { fires: false, text: 'Choose when this rule runs.' };
  if (!event) return { fires: false, text: `This server does not know the event “${selector.type}”.` };
  if (!event.firedBy.includes(host)) {
    return {
      fires: false,
      text: event.firedBy.length
        ? `${event.displayName} is not fired for ${host} content on this server, so this rule never runs.`
        : `${event.displayName} is reserved and not fired yet, so this rule never runs.`,
    };
  }
  if (selector.detail && event.details.length && !event.details.includes(selector.detail)) {
    return { fires: false, text: `${event.displayName} never fires with “${selector.detail}”; expected ${event.details.join(' or ')}.` };
  }
  return {
    fires: true,
    text: `${event.description}${selector.detail ? ` (${selector.detail} only)` : event.details.length ? ' (any side)' : ''}.`,
  };
}

export interface RulePreset {
  id: string;
  label: string;
  rule: Json;
}

const PRESETS: { id: string; label: string; on: string; action: string; params: Json }[] = [
  { id: 'click-message', label: 'Right-click sends a message', on: 'click:right', action: 'message', params: { text: '&aHello!' } },
  { id: 'click-command', label: 'Right-click runs a command', on: 'click:right', action: 'run_command', params: { command: 'say %player% used me' } },
  { id: 'interact-message', label: 'Right-click on the block sends a message', on: 'interact:right', action: 'message', params: { text: '&aHello!' } },
  { id: 'consume-potion', label: 'Eating gives an effect', on: 'consume', action: 'potion', params: { type: 'speed', duration: 200 } },
  { id: 'break-sound', label: 'Breaking plays a sound', on: 'break', action: 'sound', params: { sound: 'minecraft:block.note_block.pling' } },
  { id: 'place-message', label: 'Placing sends a message', on: 'place', action: 'message', params: { text: '&7Placed.' } },
];

/**
 * Starting points written in the existing rule format. A preset is offered
 * only when this server fires its event for the host and registers its action.
 */
export function presetsFor(vocabulary: BehaviorVocabulary | null, host: string): RulePreset[] {
  if (!vocabulary) return [];
  return PRESETS.filter((preset) => {
    const selector = parseSelector(preset.on);
    const event = eventInfo(vocabulary, selector.type);
    return Boolean(event?.firedBy.includes(host) &&
      (!selector.detail || event.details.includes(selector.detail)) &&
      actionInfo(vocabulary, preset.action));
  }).map((preset) => ({
    id: preset.id,
    label: preset.label,
    rule: { on: preset.on, do: [writeAction({
      type: preset.action, params: clone(preset.params), options: {}, scalar: false, valid: true,
    }, vocabulary)] },
  }));
}

/** A blank rule on the host's first fired event, so the form starts from something that runs. */
export function blankRule(vocabulary: BehaviorVocabulary | null, host: string): Json {
  const event = eventsFor(vocabulary, host)[0];
  const on = event ? formatSelector({ type: event.id, detail: event.details[0] ?? '' }) : '';
  const message = actionInfo(vocabulary, 'message');
  if (message) return { on, do: [{ message: { text: 'Hello!' } }] };
  const first = vocabulary?.actions[0];
  return { on, do: first ? [{ [first.id]: {} }] : [] };
}
