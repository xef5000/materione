/**
 * Which field a diagnostic is about (logic.md section 7.4, 31). The compiler reports problems at a step path, so the
 * canvas can already draw one on the right node; the inspector goes one step further and puts it on the field it names
 * when it can tell. A diagnostic is attributed to a field when its path goes on into the field's key
 * (`behavior.0.on`, `behavior.0.do.1.if`), or when its message names the parameter in quotes. Anything else stays on
 * the node, which is honest: the compiler's verdict is the compiler's, and the editor does not guess past it.
 *
 * Pure of the DOM and of Svelte.
 */
export interface FlagLike {
  severity: 'error' | 'warn' | 'info';
  message: string;
  /** The step path the compiler reported (`behavior.0.do.1`), when it came from the compiler. */
  path?: string;
}

export interface Attributed<T extends FlagLike> {
  /** Diagnostics that name no field of the node. */
  node: T[];
  /** Diagnostics by the key of the field they name. */
  fields: Map<string, T[]>;
}

/**
 * Splits a node's flags into the ones for its fields and the ones for the node itself. A flag names a field by its path
 * (it goes on into the field's key), by quoting the parameter's name, or by quoting a token that the field's current text
 * holds (the compiler's "'bogus' is not a variable of this trigger's context" lands on the template that says `bogus`).
 * `values` is the text of each field by key.
 */
export function attribute<T extends FlagLike>(
  nodeId: string,
  flags: readonly T[],
  keys: readonly string[],
  values: Readonly<Record<string, string>> = {},
): Attributed<T> {
  const out: Attributed<T> = { node: [], fields: new Map() };
  const known = new Set(keys);
  for (const flag of flags) {
    let key: string | undefined;
    if (flag.path && flag.path.startsWith(`${nodeId}.`)) {
      const rest = flag.path.slice(nodeId.length + 1).split('.');
      key = [...rest].reverse().find((segment) => known.has(segment));
    }
    if (!key) {
      key = keys.find((candidate) => new RegExp(`['"\`]${escape(candidate)}['"\`]`).test(flag.message));
    }
    if (!key) {
      const quoted = [...flag.message.matchAll(/'([^']{2,})'/g)].map((match) => match[1]!);
      key = Object.keys(values).find((candidate) => quoted.some((token) => values[candidate]!.includes(token)));
    }
    if (key) out.fields.set(key, [...(out.fields.get(key) ?? []), flag]);
    else out.node.push(flag);
  }
  return out;
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** The most serious severity of some flags, for a field's border. */
export function worstOf(flags: readonly FlagLike[]): 'error' | 'warn' | 'info' | null {
  if (flags.some((flag) => flag.severity === 'error')) return 'error';
  if (flags.some((flag) => flag.severity === 'warn')) return 'warn';
  return flags.length > 0 ? 'info' : null;
}
