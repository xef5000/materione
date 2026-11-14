/**
 * How a registry id becomes a member name of the SDK (codemode.md section 3.8, Names). The generator spells
 * the generated declarations with these functions and the core (`../core`) spells the run-time object with
 * the same ones, so a member the types promise and a member the runtime has cannot be named differently:
 * there is one naming policy and it lives here. Pure: it reads ids, nothing else.
 */
import type { SurfaceContentType } from './surface';

export function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function sortedBy<T>(items: readonly T[], key: (item: T) => string): T[] {
  return [...items].sort((a, b) => compare(key(a), key(b)));
}

export function words(text: string): string[] {
  return text.split(/[^A-Za-z0-9]+/).filter((word) => word.length > 0);
}

export function capital(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

/** `run_command` and `gui.open` become `runCommand` and `guiOpen`. */
export function camel(text: string): string {
  const parts = words(text);
  const name = parts.map((word, index) => (index === 0 ? word.charAt(0).toLowerCase() + word.slice(1) : capital(word))).join('');
  if (name === '') return '_';
  return /^[0-9]/.test(name) ? `_${name}` : name;
}

export function pascal(text: string): string {
  const name = words(text).map(capital).join('');
  if (name === '') return '_';
  return /^[0-9]/.test(name) ? `_${name}` : name;
}

/**
 * The member name of every entry of one catalog: the camel-cased local part of its id. Two entries whose
 * local parts collide are told apart by their namespaces (`example:greet` and `other:greet` become
 * `exampleGreet` and `otherGreet`); an id without a namespace is never renamed. `reserved` names are taken
 * by the core and get a trailing underscore.
 */
export function memberNames(ids: readonly string[], reserved: ReadonlySet<string> = new Set()): Map<string, string> {
  const parts = ids.map((id) => {
    const at = id.indexOf(':');
    return { id, namespace: at < 0 ? '' : id.slice(0, at), local: at < 0 ? id : id.slice(at + 1) };
  });
  const count = new Map<string, number>();
  for (const part of parts) {
    const base = camel(part.local);
    count.set(base, (count.get(base) ?? 0) + 1);
  }
  const taken = new Set<string>();
  const result = new Map<string, string>();
  for (const part of sortedBy(parts, (p) => p.id)) {
    const base = camel(part.local);
    let name = (count.get(base) ?? 0) > 1 && part.namespace !== '' ? camel(`${part.namespace} ${part.local}`) : base;
    if (reserved.has(name)) name = `${name}_`;
    while (taken.has(name)) name = `${name}_`;
    taken.add(name);
    result.set(part.id, name);
  }
  return result;
}

/** The member of `PackWriter` each content type's collection gets, keyed by its directory (the type name when it has none). `id` is the writer's own. */
export function writerMembers(types: readonly SurfaceContentType[]): Map<string, string> {
  return memberNames(types.map((type) => type.directory ?? type.name), new Set(['id']));
}

/** The member of `m.ref` each content type's lookup gets, keyed by the type name. `asset` is the factory's own. */
export function refMembers(types: readonly SurfaceContentType[]): Map<string, string> {
  return memberNames(types.map((type) => type.name), new Set(['asset']));
}
