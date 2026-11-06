/**
 * Declared variables as the inspector offers them (logic.md section 3, 20, 31): the `variables/` files of the
 * packs, read through the ordinary document ops, and the "declare new" shortcut that writes a declaration into the
 * pack's variables file through the ordinary authoring ops. Nothing is kept in the editor (D1): the picker reads the
 * files, and a new declaration is a field edit like any other, saved by the server in one revision.
 *
 * The spelling the editor writes for a declared persistent variable is the qualified `pack:key` (section 20, decision
 * 5): the scope comes from the declaration, so the same spelling reads and writes either scope and a typo cannot
 * create a second key. Session and run-local variables need no declaration and are offered from the steps that use
 * them (`scope.ts`).
 *
 * The parsing is pure; the loading and declaring take the sealed channel and nothing else.
 */
import { ops, unwrap, type FieldEditPayload, type ResourceRef, type RpcChannel } from '../api';
import { isRecord } from './tree';

export type VariableScope = 'player' | 'server';
export type VariableType = 'number' | 'string' | 'boolean';

export const VARIABLE_SCOPES: readonly VariableScope[] = ['player', 'server'];
export const VARIABLE_TYPES: readonly VariableType[] = ['number', 'string', 'boolean'];

export interface DeclaredVariable {
  /** `pack:key`, the spelling a step uses. */
  id: string;
  pack: string;
  key: string;
  scope: VariableScope;
  type: VariableType;
  default: unknown;
  description: string;
  /** The file that declares it. */
  path: string;
}

/** What a new declaration is made of. */
export interface VariableSpec {
  key: string;
  scope: VariableScope;
  type: VariableType;
  default?: unknown;
  description?: string;
}

const KEY = /^[a-z][a-z0-9_]*$/;

/** Why a key cannot be declared, or null. The key follows the content id rules (lowercase, digits, underscore). */
export function keyProblem(key: string, existing: readonly DeclaredVariable[], pack: string): string | null {
  if (!key) return 'Name the variable.';
  if (!KEY.test(key)) return 'Use lowercase letters, digits and underscores, starting with a letter.';
  if (['local', 'session', 'player', 'server'].includes(key)) return `"${key}" is a scope name and cannot be a variable.`;
  if (existing.some((variable) => variable.pack === pack && variable.key === key)) return `${pack}:${key} is already declared.`;
  return null;
}

/** The default a declaration of a type starts with (what the compiler coerces an omitted default to). */
export function defaultOf(type: VariableType): unknown {
  return type === 'number' ? 0 : type === 'boolean' ? false : '';
}

/** The declarations in one parsed `variables/` document: each top-level mapping with a known scope and type. */
export function declarationsOf(pack: string, path: string, parsed: unknown): DeclaredVariable[] {
  if (!isRecord(parsed)) return [];
  const out: DeclaredVariable[] = [];
  for (const [key, body] of Object.entries(parsed)) {
    if (!isRecord(body)) continue;
    const scope = body.scope === 'player' || body.scope === 'server' ? body.scope : null;
    const type = body.type === 'number' || body.type === 'string' || body.type === 'boolean' ? body.type : null;
    if (!scope || !type) continue;
    out.push({
      id: `${pack}:${key}`,
      pack,
      key,
      scope,
      type,
      default: body.default ?? defaultOf(type),
      description: typeof body.description === 'string' ? body.description : '',
      path,
    });
  }
  return out;
}

/** The variables files among a pack's resources. */
export function variableFiles(resources: readonly ResourceRef[]): ResourceRef[] {
  const seen = new Set<string>();
  return resources.filter((ref) => {
    const key = `${ref.pack}/${ref.path}`;
    if (!ref.path.startsWith('variables/') || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Reads every variables file and returns the declarations in them. A file that cannot be read is skipped. */
export async function loadDeclared(channel: RpcChannel, resources: readonly ResourceRef[]): Promise<DeclaredVariable[]> {
  const out: DeclaredVariable[] = [];
  for (const file of variableFiles(resources)) {
    try {
      const data = unwrap(await ops.get(channel, file.pack, file.path));
      out.push(...declarationsOf(file.pack, file.path, data.parsed));
    } catch {
      // fail-soft: one unreadable file must not hide the others
    }
  }
  return out;
}

/** The field edits that write a declaration into an existing variables document. */
export function declarationEdits(spec: VariableSpec): FieldEditPayload[] {
  const body: Record<string, unknown> = { scope: spec.scope, type: spec.type, default: spec.default ?? defaultOf(spec.type) };
  if (spec.description) body.description = spec.description;
  return [{ field: [spec.key], value: body }];
}

/**
 * Declares a variable in `pack`'s variables file, creating `variables/<pack>.yml` first when the pack has none. Both
 * steps are the ordinary authoring ops (`edit.create`, `authoring.setFields`), so the server validates and saves them
 * as it does any edit. The new document is born holding the key as a player number, and the second step sets what the
 * author asked for.
 */
export async function declareVariable(
  channel: RpcChannel,
  pack: string,
  spec: VariableSpec,
  resources: readonly ResourceRef[],
): Promise<DeclaredVariable> {
  const own = variableFiles(resources).filter((ref) => ref.pack === pack);
  const file = own.find((ref) => ref.path === `variables/${pack}.yml`) ?? own[0];
  if (file) {
    const data = unwrap(await ops.get(channel, pack, file.path));
    if (isRecord(data.parsed) && Object.hasOwn(data.parsed, spec.key)) throw new Error(`${pack}:${spec.key} is already declared.`);
    unwrap(await ops.setFields(channel, pack, file.path, Number(data.rev), declarationEdits(spec)));
    return finish(pack, file.path, spec);
  }
  const path = `variables/${pack}.yml`;
  const created = unwrap(await ops.create(channel, pack, path, spec.key));
  const edits: FieldEditPayload[] = [
    { field: [spec.key, 'scope'], value: spec.scope },
    { field: [spec.key, 'type'], value: spec.type },
    { field: [spec.key, 'default'], value: spec.default ?? defaultOf(spec.type) },
    ...(spec.description ? [{ field: [spec.key, 'description'], value: spec.description }] : []),
  ];
  unwrap(await ops.setFields(channel, pack, path, Number(created.rev), edits));
  return finish(pack, path, spec);
}

function finish(pack: string, path: string, spec: VariableSpec): DeclaredVariable {
  return {
    id: `${pack}:${spec.key}`,
    pack,
    key: spec.key,
    scope: spec.scope,
    type: spec.type,
    default: spec.default ?? defaultOf(spec.type),
    description: spec.description ?? '',
    path,
  };
}

/** A declared variable's reading in an expression: `vars.player.<pack>.<key>` (section 20, decision 4). */
export function readPath(variable: DeclaredVariable): string {
  return `vars.${variable.scope}.${variable.pack}.${variable.key}`;
}

/** How a default reads in the picker, briefly. */
export function describeDefault(variable: DeclaredVariable): string {
  return typeof variable.default === 'string' ? `"${variable.default}"` : String(variable.default);
}
