/**
 * The engine's closed grammar as the surface document describes it (issue 355): the step kinds (`m.steps`), the
 * composite conditions and the flow keys of a rule. Nothing here names a step, a condition word or a rule key:
 * each builder is made from a form of the surface (its `payload`, its `siblings`, the grammar kind of each
 * parameter), the way every other member of `m` is made from a registry entry. The only words this file knows
 * are the schema's: the grammar kinds of a parameter and its value types.
 */
import { KIND_ARGUMENTS, KIND_CONDITION, KIND_CONDITIONS, KIND_FLOW, KIND_STEPS, type SurfaceParam, type SurfaceStepKind } from '../generate/surface';
import { camel, sortedBy } from '../generate/names';
import type { JsonValue } from './types';

/** What the builders need from the session: serialization with the program's own error reporting. */
export interface FormContext {
  /** Any value as a document tree. */
  node(value: unknown, where: string): JsonValue;
  /** A condition (a built condition or an expression). */
  condition(value: unknown, where: string): JsonValue;
  /** A list of steps. */
  steps(value: unknown, where: string): JsonValue[];
  /** Whether `value` is a plain mapping the program wrote. */
  plain(value: unknown): value is Record<string, unknown>;
  /** Records the diagnostic and ends the run. */
  invalid(message: string): never;
  /** Wraps a finished tree as a value of the catalog (a step, a condition). */
  wrap(node: JsonValue): unknown;
}

type Dict = Record<string, JsonValue>;

const TYPE_INTEGER = 'integer';
const TYPE_BOOLEAN = 'boolean';
const TYPE_LIST = 'list';

function range(param: SurfaceParam): string {
  if (param.min !== undefined && param.max !== undefined) return `, ${param.min} to ${param.max}`;
  if (param.min !== undefined) return `, ${param.min} or more`;
  if (param.max !== undefined) return `, ${param.max} at most`;
  return '';
}

/**
 * One value as the registry's parameter describes it: by its grammar kind when it names one, as a document tree
 * otherwise. `strict` also holds a whole number, a boolean and a nested list to their declared shape (the forms
 * of the grammar are closed, so a wrong value is the program's mistake and not an unknown extension).
 */
export function writeParam(c: FormContext, param: SurfaceParam, value: unknown, where: string, strict: boolean): JsonValue {
  switch (param.valueKind) {
    case KIND_STEPS:
      return c.steps(value, where);
    case KIND_CONDITION:
      return c.condition(value, where);
    case KIND_CONDITIONS:
      if (!Array.isArray(value)) return c.invalid(`${where}: a list of conditions is expected`);
      return value.map((entry, index) => c.condition(entry, `${where}[${index}]`));
    case KIND_FLOW:
      if (typeof value !== 'string' || value === '') return c.invalid(`${where}: the name of a flow is expected`);
      return value;
    case KIND_ARGUMENTS:
      if (!c.plain(value)) return c.invalid(`${where}: a mapping of argument names to values is expected`);
      return c.node(value, where);
    default:
      break;
  }
  if (!strict) return c.node(value, where);
  if (param.type === TYPE_INTEGER) {
    const whole = typeof value === 'number' && Number.isInteger(value);
    if (!whole || (param.min !== undefined && (value as number) < param.min) || (param.max !== undefined && (value as number) > param.max)) {
      return c.invalid(`${where}: a whole number is expected${range(param)}`);
    }
    return value as number;
  }
  if (param.type === TYPE_BOOLEAN) {
    if (typeof value !== 'boolean') return c.invalid(`${where}: true or false is expected`);
    return value;
  }
  if (param.type === TYPE_LIST && param.nested !== undefined && param.nested.length > 0) {
    if (!Array.isArray(value) || (param.required && value.length === 0)) return c.invalid(`${where}: a list with at least one entry is expected`);
    return value.map((entry, index) => {
      if (!c.plain(entry)) return c.invalid(`${where}[${index}]: a mapping is expected`);
      return writeMembers(c, param.nested ?? [], entry, `${where}[${index}]`, true);
    });
  }
  return c.node(value, where);
}

/** A mapping written member by member from the parameters that describe it: no stray key, every required one. */
export function writeMembers(c: FormContext, params: readonly SurfaceParam[], given: Record<string, unknown>, where: string, strict: boolean): Dict {
  const byId = new Map(params.map((param) => [param.id, param]));
  for (const key of Object.keys(given)) {
    if (given[key] !== undefined && !byId.has(key)) c.invalid(`${where} has no "${key}"; it takes: ${params.map((param) => param.id).join(', ') || 'nothing'}`);
  }
  const out: Dict = {};
  for (const param of params) {
    const value = given[param.id];
    if (value === undefined) {
      if (param.required) c.invalid(`${where} needs "${param.id}"`);
      continue;
    }
    out[param.id] = writeParam(c, param, value, `${where} ${param.id}`, strict);
  }
  return out;
}

/**
 * The builders of a list of forms, one member per form the engine runs and writes by its own key. A form with a
 * payload is `{ id: payload, ...siblings }`; a form without one is `{ id: { ...parameters } }`, or the bare id
 * when it is given none.
 */
export function formBuilders(forms: readonly SurfaceStepKind[], ownerPrefix: string, c: FormContext): Record<string, (...args: unknown[]) => unknown> {
  const builders: Record<string, (...args: unknown[]) => unknown> = {};
  for (const form of sortedBy(forms.filter((entry) => entry.supported && entry.registry === undefined), (entry) => entry.id)) {
    const owner = `${ownerPrefix}.${camel(form.id)}`;
    const byId = new Map(form.parameters.map((param) => [param.id, param]));
    const payload = form.payload === undefined ? undefined : byId.get(form.payload);
    const mapping = (value: unknown): Record<string, unknown> => {
      if (value === undefined) return {};
      if (!c.plain(value)) return c.invalid(`${owner}: the options are a mapping`);
      return value;
    };
    if (payload === undefined) {
      builders[camel(form.id)] = (options: unknown): unknown => {
        const body = writeMembers(c, form.parameters, mapping(options), owner, true);
        return c.wrap(Object.keys(body).length === 0 ? form.id : { [form.id]: body });
      };
      continue;
    }
    const siblings = form.siblings.flatMap((id) => byId.get(id) ?? []);
    builders[camel(form.id)] = (value: unknown, options: unknown): unknown => {
      if (value === undefined) c.invalid(`${owner} needs a value`);
      const node: Dict = { [form.id]: writeParam(c, payload, value, owner, true) };
      const beside = writeMembers(c, siblings, mapping(options), owner, true);
      if (form.oneOf.length > 0 && !form.oneOf.some((id) => beside[id] !== undefined)) {
        c.invalid(`${owner} needs at least one of: ${form.oneOf.join(', ')}`);
      }
      for (const key of Object.keys(beside)) node[key] = beside[key] as JsonValue;
      return c.wrap(node);
    };
  }
  return builders;
}
