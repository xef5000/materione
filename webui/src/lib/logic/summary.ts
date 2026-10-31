/**
 * One-line text for the values a node shows (a condition, a map of parameters, a list of filters). The
 * node is a summary and the inspector (issue 224) is the editor, so what is long is cut, and what is
 * structured is named: `all of 2` for a composite condition, `item: apple, amount: 2` for a map.
 *
 * Pure of the DOM.
 */
import { isRecord } from './tree';

export function summarize(value: unknown, max = 64): string {
  const text = describe(value);
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function describe(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.map(describe).join(', ');
  if (isRecord(value)) {
    const keys = Object.keys(value);
    if (keys.length === 1) {
      const key = keys[0]!;
      const inner = value[key];
      if ((key === 'all' || key === 'any') && Array.isArray(inner)) return `${key} of ${inner.length}`;
      if (key === 'not') return `not ${describe(inner)}`;
      if (key === 'at_least' && isRecord(inner)) return `at least ${describe(inner.count)} of ${Array.isArray(inner.of) ? inner.of.length : '?'}`;
      return `${key}: ${describe(inner)}`;
    }
    return keys.map((key) => `${key}: ${describe(value[key])}`).join(', ');
  }
  return String(value);
}

/** Whether a node's primary value can be edited as one line of text. */
export function isLine(value: unknown): value is string | number {
  return typeof value === 'string' || typeof value === 'number';
}
