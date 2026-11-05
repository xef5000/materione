/**
 * Parameters as the inspector edits them (logic.md section 7.3, 31): which field a parameter is drawn as, what an
 * authored value looks like to that field, and how a changed value is written back into the step.
 *
 * Everything the inspector draws comes from the self-description (D5): the parameter's type, its widget hint
 * (`uiHint`), its allowed values and its default. This module is the one place that turns that into a field kind, so
 * the node inspector, the condition editor and the trigger filters agree. A value the field cannot hold (a template
 * where a number is expected, a mapping where a text is) falls back to a field that can, and never loses it.
 *
 * Pure of the DOM and of Svelte.
 */
import type { ParamInfo } from './vocabulary';
import { isRecord, type Json } from './tree';

export type FieldKind =
  | 'item'
  | 'sound'
  | 'variable'
  | 'inputMethod'
  | 'color'
  | 'reference'
  | 'boolean'
  | 'enum'
  | 'number'
  | 'list'
  | 'raw'
  | 'template';

/** The widget hints the editor draws specially; any other hint is drawn by the parameter's type. */
const HINT_KIND: Record<string, FieldKind> = {
  item: 'item',
  sound: 'sound',
  variable: 'variable',
  'input-method': 'inputMethod',
  color: 'color',
  colour: 'color',
  reference: 'reference',
  toggle: 'boolean',
  select: 'enum',
  number: 'number',
  list: 'list',
  advanced: 'raw',
};

/** The field a parameter is drawn as when its value is absent or fits it. */
export function baseKind(param: ParamInfo): FieldKind {
  const hinted = param.uiHint ? HINT_KIND[param.uiHint] : undefined;
  if (hinted === 'item' || hinted === 'sound' || hinted === 'variable' || hinted === 'inputMethod' || hinted === 'color') {
    return hinted;
  }
  if (param.allowedValues && param.allowedValues.length > 0) return 'enum';
  if (param.options && param.options.length > 0) return 'enum';
  switch (param.type) {
    case 'boolean':
      return 'boolean';
    case 'integer':
    case 'decimal':
      return 'number';
    case 'list':
      return 'list';
    case 'raw':
      return 'raw';
    case 'reference':
      return 'reference';
    case 'enum':
      return 'enum';
    default:
      break;
  }
  return hinted ?? 'template';
}

/** The values an enum parameter offers: labelled choices when the description gives them, else the raw values. */
export function choicesOf(param: ParamInfo): { value: string; label: string; description: string }[] {
  if (param.options && param.options.length > 0) return param.options;
  return (param.allowedValues ?? []).map((value) => ({ value, label: value.replaceAll('_', ' '), description: '' }));
}

function isNumeric(text: string): boolean {
  return /^-?\d+(\.\d+)?$/.test(text.trim());
}

/**
 * The field that shows `value` for `param`. The base kind when the value fits it; otherwise the nearest field that
 * keeps the value intact: rows for a list, a template for text where a number or a choice belongs, raw JSON for a
 * mapping. `element` is the kind of one row when the field is a list of a scalar parameter.
 */
export function kindFor(param: ParamInfo, value: unknown): { kind: FieldKind; element?: FieldKind } {
  const base = baseKind(param);
  if (value === undefined || value === null) return { kind: base };
  if (Array.isArray(value)) {
    if (base === 'list' || base === 'raw') return { kind: base };
    return { kind: 'list', element: base === 'boolean' || base === 'number' || base === 'enum' ? 'template' : base };
  }
  if (isRecord(value)) return { kind: 'raw' };
  switch (base) {
    case 'boolean':
      return typeof value === 'boolean' || value === 'true' || value === 'false' ? { kind: 'boolean' } : { kind: 'template' };
    case 'number':
      if (typeof value === 'number') return { kind: 'number' };
      return typeof value === 'string' && isNumeric(value) ? { kind: 'number' } : { kind: 'template' };
    case 'enum': {
      const allowed = choicesOf(param).map((choice) => choice.value);
      return typeof value === 'string' && (allowed.includes(value) || allowed.length === 0) ? { kind: 'enum' } : { kind: 'template' };
    }
    case 'list':
    case 'raw':
      return { kind: base };
    default:
      return typeof value === 'string' ? { kind: base } : { kind: 'raw' };
  }
}

// ------------------------------------------------------------------ payloads

/**
 * The parameters an authored payload holds, as the compiler reads them: a mapping is its own parameters, a scalar or
 * a list fills the shorthand parameter, and nothing is no parameters.
 */
export function readPayload(payload: unknown, shorthand?: string): Json {
  if (payload === undefined || payload === null) return {};
  if (isRecord(payload)) return payload;
  return shorthand ? { [shorthand]: payload } : {};
}

/** The parameter keys a payload holds that the self-description does not list: kept, shown read-only, never dropped. */
export function unknownKeys(payload: unknown, parameters: readonly ParamInfo[]): string[] {
  if (!isRecord(payload)) return [];
  const known = new Set(parameters.map((param) => param.id));
  return Object.keys(payload).filter((key) => !known.has(key));
}

/**
 * The payload after `id` is set to `value` (`undefined` removes it). The form the author chose stays: a scalar that
 * still holds only the shorthand stays a scalar, a mapping stays a mapping, and a bare step that gets its first
 * parameter becomes the compact scalar when that parameter is the shorthand, else a mapping. Key order is kept.
 */
export function writeParam(payload: unknown, shorthand: string | undefined, id: string, value: unknown): unknown {
  const scalar = payload !== undefined && payload !== null && !isRecord(payload);
  const next: Json = { ...readPayload(payload, shorthand) };
  if (value === undefined) delete next[id];
  else next[id] = value;
  const keys = Object.keys(next);
  const compact = scalar || payload === undefined || payload === null;
  if (compact && shorthand && keys.every((key) => key === shorthand)) {
    return keys.length === 0 ? '' : next[shorthand];
  }
  return next;
}

/** Whether a value counts as "not set" for a parameter that is not required: the key is then removed, not written blank. */
export function isBlank(value: unknown): boolean {
  return value === undefined || value === null || value === '' || (Array.isArray(value) && value.length === 0);
}

/** What a field with nothing in it holds: nothing for an optional parameter, the starting value for a required one. */
export function emptyValue(param: ParamInfo): unknown {
  return param.required ? '' : undefined;
}

/** The text a number field shows for a value. */
export function numberText(value: unknown): string {
  return typeof value === 'number' || (typeof value === 'string' && value !== '') ? String(value) : '';
}

/** The number a number field commits for typed text; the text itself when it is not a plain number (an expression). */
export function numberValue(text: string, param: ParamInfo): unknown {
  const trimmed = text.trim();
  if (trimmed === '') return undefined;
  if (!isNumeric(trimmed)) return trimmed;
  const parsed = Number(trimmed);
  const clamped = Math.min(param.max ?? Infinity, Math.max(param.min ?? -Infinity, parsed));
  return param.type === 'integer' ? Math.round(clamped) : clamped;
}

/** A one-line, honest rendering of a value no field can edit (a mapping the self-description does not describe). */
export function jsonLine(value: unknown): string {
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return String(value);
  }
}

/** The text of each string-valued parameter of a payload by key (lists joined), for matching a diagnostic to the field that says it. */
export function textValues(payload: unknown, shorthand?: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(readPayload(payload, shorthand))) {
    if (typeof value === 'string') out[key] = value;
    else if (Array.isArray(value)) out[key] = value.filter((each): each is string => typeof each === 'string').join('\n');
  }
  return out;
}
