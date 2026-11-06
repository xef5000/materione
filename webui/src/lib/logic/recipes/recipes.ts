/**
 * Recipes as the studio reads them (logic.md section 4.6, 7.5, 30, 33). The registry is the server's: the vocabulary lists
 * each recipe with its typed parameters and `logic.instantiate` expands one into rules. This module only reads that
 * list, turns a recipe parameter into the self-description the node inspector's typed fields draw (so a recipe form
 * is the same set of controls as an action's), and plans the insertion of the expanded rules through `logic.edit`
 * (D6: the studio does not write rules any other way).
 *
 * Pure of the DOM and of Svelte.
 */
import type { Plan } from '../plan';
import { refuse } from '../plan';
import type { ParamInfo } from '../vocabulary';
import { clone, isRecord, type Json, type LogicBlock } from '../tree';

export type RecipeParamType = 'string' | 'number' | 'integer' | 'boolean' | 'variable';

export interface RecipeParam {
  name: string;
  type: RecipeParamType | string;
  required: boolean;
  default?: unknown;
  description: string;
  min?: number;
  max?: number;
  /** A `variable` parameter: the scope and value type the recipe needs the declared variable to have. */
  scope?: string;
  valueType?: string;
}

export interface RecipeEntry {
  id: string;
  displayName: string;
  description: string;
  params: RecipeParam[];
  registeredBy: string;
  /** False when the server cannot run an action the recipe writes; the reason is the server's. */
  available: boolean;
  reason?: string;
}

const text = (value: unknown, fallback = ''): string => (typeof value === 'string' ? value : fallback);

/**
 * The recipes of a `logic.vocabulary` reply, by id. A server that does not list recipes (an older plugin) has none.
 * A recipe entry that carries `available: false` (a server that knows its recipes' availability) is greyed with the
 * reason it gives; the studio otherwise finds that out by asking for an expansion (`RecipesState.probe`).
 */
export function readRecipes(raw: unknown): Map<string, RecipeEntry> {
  const out = new Map<string, RecipeEntry>();
  if (!Array.isArray(raw)) return out;
  for (const entry of raw.filter(isRecord)) {
    const id = text(entry.id);
    if (!id) continue;
    out.set(id, {
      id,
      displayName: text(entry.displayName, id),
      description: text(entry.description),
      params: (Array.isArray(entry.params) ? entry.params.filter(isRecord) : [])
        .map((param): RecipeParam => ({
          name: text(param.name),
          type: text(param.type, 'string'),
          required: param.required === true,
          default: param.default,
          description: text(param.description),
          min: typeof param.min === 'number' ? param.min : undefined,
          max: typeof param.max === 'number' ? param.max : undefined,
          scope: typeof param.scope === 'string' ? param.scope : undefined,
          valueType: typeof param.valueType === 'string' ? param.valueType : undefined,
        }))
        .filter((param) => param.name),
      registeredBy: text(entry.registeredBy),
      ...(entry.available === false
        ? { available: false, reason: text(entry.unavailableReason, 'This server cannot run it.') }
        : { available: true }),
    });
  }
  return out;
}

// ------------------------------------------------------------------ the form

/** The parameters that name a layer of the GUI: drawn as a choice of its layers instead of free text. */
function namesLayer(param: RecipeParam): boolean {
  return param.type === 'string' && (['layer', 'list', 'previous', 'next'].includes(param.name) || /\blayer\b/i.test(param.description));
}

/**
 * A recipe parameter as the typed field editors take it (the node inspector's `ParamInfo`), so the recipe form is made
 * of the same controls: a number field, a toggle, the variable picker and the template field. A parameter that names a
 * layer offers the GUI's layers.
 */
export function paramInfo(param: RecipeParam, layers: readonly string[] = []): ParamInfo {
  const base: ParamInfo = {
    id: param.name,
    label: param.name.replaceAll('_', ' '),
    type: 'string',
    required: param.required,
    default: param.default === undefined || param.default === null || param.default === '' ? undefined : String(param.default),
    description: param.description || undefined,
    min: param.min,
    max: param.max,
  };
  switch (param.type) {
    case 'number':
      return { ...base, type: 'decimal', uiHint: 'number' };
    case 'integer':
      return { ...base, type: 'integer', uiHint: 'number' };
    case 'boolean':
      return { ...base, type: 'boolean', uiHint: 'toggle' };
    case 'variable':
      return { ...base, uiHint: 'variable' };
    default:
      return namesLayer(param) && layers.length > 0 ? { ...base, uiHint: 'select', allowedValues: [...layers] } : base;
  }
}

/** The values the form starts with: every parameter's default, and `layer` the layer selected in the Design tab. */
export function startingValues(recipe: RecipeEntry, layer?: string): Json {
  const values: Json = {};
  for (const param of recipe.params) {
    if (param.default !== undefined && param.default !== null && param.default !== '') values[param.name] = param.default;
  }
  if (layer && recipe.params.some((param) => param.name === 'layer' && param.type === 'string')) values.layer = layer;
  return values;
}

/** Whether a form value counts as left blank. */
const blank = (value: unknown): boolean => value === undefined || value === null || value === '';

/** The required parameters the values leave blank. */
export function missingRequired(recipe: RecipeEntry, values: Readonly<Json>): string[] {
  return recipe.params.filter((param) => param.required && blank(values[param.name])).map((param) => param.name);
}

/** The parameters to send: the blank optional ones are left out, so the recipe's own default applies. */
export function sendable(recipe: RecipeEntry, values: Readonly<Json>): Json {
  const out: Json = {};
  for (const param of recipe.params) {
    const value = values[param.name];
    if (!blank(value)) out[param.name] = value;
  }
  return out;
}

/**
 * Parameters that make a recipe expand without the author: its defaults, and a stand-in of each required kind. They are
 * only sent to find out which actions the recipe writes and whether the server can run them (`RecipesState.probe`);
 * nothing is written. A recipe with a variable parameter cannot be probed (a stand-in would name a variable that is not
 * declared), and the answer is `null`.
 */
export function standIns(recipe: RecipeEntry): Json | null {
  const values: Json = {};
  for (const param of recipe.params) {
    if (!param.required) continue;
    if (param.default !== undefined && param.default !== null && param.default !== '') {
      values[param.name] = param.default;
      continue;
    }
    switch (param.type) {
      case 'variable':
        return null;
      case 'number':
        values[param.name] = Math.max(param.min ?? 1, 1);
        break;
      case 'integer':
        values[param.name] = Math.ceil(Math.max(param.min ?? 1, 1));
        break;
      case 'boolean':
        values[param.name] = false;
        break;
      default:
        values[param.name] = 'stand_in';
    }
  }
  return values;
}

// ------------------------------------------------------------------ inserting

/** The rules a recipe expanded to, appended to `behavior:` as one plan (one undo step); the first is selected. */
export function planInsertRecipe(block: LogicBlock, rules: readonly unknown[], name: string): Plan {
  const usable = rules.filter(isRecord);
  if (usable.length === 0) return refuse(`${name} expanded to no rules.`);
  const start = block.behavior?.length ?? 0;
  return {
    ok: true,
    edits: usable.map((rule, offset) => ({ op: 'addRule' as const, index: start + offset, rule: clone(rule) })),
    label: `Insert ${name}`,
    select: `behavior.${start}`,
  };
}
