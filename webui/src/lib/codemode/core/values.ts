/**
 * What the core's values are made of: the registry of values the core built, the plain-data test, the
 * nearest-id search and the document path of a created resource. Pure and dependency-free.
 *
 * A value the core builds (a step, a condition, a reference, an expression, a context value, a trigger) is a
 * frozen object that the core also records in a `WeakMap` under what it stands for. A program cannot forge
 * one: an object it writes by hand is plain data, serialized as the mapping it is. The registry is private to
 * the module, so the only way to make a tagged value is through the core.
 */
import type { JsonValue } from './types';

/** What a core-built value stands for. */
export type Tagged =
  /** A step, condition, mechanic entry, GUI layer, item source or list source: already the document tree it writes. */
  | { tag: 'node'; node: JsonValue }
  | { tag: 'trigger'; id: string; detail: string | null; filters: [string, JsonValue][] }
  | { tag: 'recipeInstance'; id: string; params: JsonValue }
  | { tag: 'expr'; source: string }
  | { tag: 'context'; path: string }
  | { tag: 'ref'; id: string; kind: string }
  | { tag: 'rule' };

const registry = new WeakMap<object, Tagged>();

/** Freezes `value` and records what it stands for. */
export function tagged<T extends object>(value: T, info: Tagged): T {
  registry.set(value, info);
  return Object.freeze(value);
}

/** Records what `value` stands for without freezing it (a Proxy, or an object that gets members later). */
export function tag<T extends object>(value: T, info: Tagged): T {
  registry.set(value, info);
  return value;
}

export function tagOf(value: unknown): Tagged | undefined {
  return typeof value === 'object' && value !== null ? registry.get(value) : undefined;
}

/**
 * A plain object: `{}` or an object literal, from any realm (the program may run in another realm than the
 * core, so the prototype is not compared).
 */
export function isPlain(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.apply(value) === '[object Object]' && tagOf(value) === undefined;
}

export function distance(a: string, b: string): number {
  let previous: number[] = [];
  for (let j = 0; j <= b.length; j += 1) previous.push(j);
  for (let i = 1; i <= a.length; i += 1) {
    const row: number[] = [i];
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a.charAt(i - 1) === b.charAt(j - 1) ? 0 : 1;
      row.push(Math.min((previous[j] ?? 0) + 1, (row[j - 1] ?? 0) + 1, (previous[j - 1] ?? 0) + cost));
    }
    previous = row;
  }
  return previous[b.length] ?? 0;
}

/** The `count` candidates nearest to `wanted`, nearest first, ties by text: deterministic. */
export function nearest(wanted: string, candidates: readonly string[], count = 3): string[] {
  const unique = [...new Set(candidates)];
  return unique
    .map((candidate) => ({ candidate, d: distance(wanted.toLowerCase(), candidate.toLowerCase()) }))
    .sort((x, y) => x.d - y.d || (x.candidate < y.candidate ? -1 : x.candidate > y.candidate ? 1 : 0))
    .slice(0, count)
    .map((entry) => entry.candidate);
}

/** The UTF-8 length of a string, without an encoder (none is guaranteed in the sandbox). */
export function byteLength(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    if (code < 0x80) bytes += 1;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff) {
      bytes += 4;
      i += 1;
    } else bytes += 3;
  }
  return bytes;
}

/** A resource id as the server accepts it (`AuthoringApiImpl.documentPath`): 2 to 64 of a-z, 0-9, `_`, `-`. */
export function validId(id: unknown): id is string {
  return typeof id === 'string' && /^[a-z0-9_-]{2,64}$/.test(id);
}

/**
 * The path of a document a `create` or `duplicate` writes, as the server computes it
 * (`AuthoringApiImpl.documentPath`): `<directory>/[folder/]<id>.yml`, the folder cleaned of surrounding slashes and of a leading
 * copy of the directory. Returns null for a folder the server would refuse.
 */
export function documentPath(directory: string, folder: string | undefined, id: string): string | null {
  let prefix = directory;
  if (folder !== undefined && folder.trim() !== '') {
    let cleaned = folder.replace(/^\/+|\/+$/g, '');
    if (cleaned.indexOf(`${directory}/`) === 0) cleaned = cleaned.slice(directory.length + 1);
    else if (cleaned === directory) cleaned = '';
    if (cleaned !== '') {
      if (!/^[A-Za-z0-9_ -]+(\/[A-Za-z0-9_ -]+)*$/.test(cleaned)) return null;
      prefix = `${directory}/${cleaned}`;
    }
  }
  return `${prefix}/${id}.yml`;
}
