/**
 * The SDK core (codemode.md section 3.1 to 3.4, issue 339): builds `m`, the program's one global, from a
 * surface document, and turns the program's calls into an ordered list of staged operations that is, member
 * for member, an `authoring.transaction` operation list.
 *
 * Generic by construction (D5, master section 5.6). Nothing here names a content type, an action, a trigger or
 * a mechanic: every member of `m` is built from a registry entry of the surface document, spelled with the
 * generator's own naming functions, so adding an entry to a registry changes the object without changing this
 * file, and the object and the generated declarations cannot disagree. The only vocabulary the core knows is
 * the schema's (value kinds and widget hints), the wire's (operation kinds, in `ops.ts`) and the step grammar
 * (`steps.ts`, temporary until issue 355).
 *
 * It never writes and never talks to the server: reads go through the injected port, everything else is
 * recorded. Dependency-free, DOM-free, plain ES2020: it runs unchanged inside the sandbox.
 */
import { CAPABILITY_LOGIC, CAPABILITY_TRANSACTION, capabilityOn } from '../generate/capabilities';
import { contextFor, contextTree, type ContextNode } from '../generate/context';
import { camel, memberNames, refMembers, sortedBy, writerMembers } from '../generate/names';
import type {
  SurfaceContentType,
  SurfaceEntry,
  SurfaceFunction,
  SurfaceParam,
  SurfaceTrigger,
} from '../generate/surface';
import { SDK_CODES, SdkError, isSdkError, type SdkCode } from './diagnostics';
import { readEntry, writeEntry, type OpEntry } from './ops';
import { FLOW, stepBuilders } from './steps';
import {
  DEFAULT_LIMITS,
  type HostPort,
  type JsonValue,
  type Limits,
  type Outcome,
  type ProgramResult,
  type SdkDiagnostic,
  type Session,
  type SessionConfig,
  type SourcePosition,
  type StagedOperation,
} from './types';
import { byteLength, documentPath, isPlain, nearest, tag, tagOf, tagged, validId } from './values';

/** The core's own version (codemode.md section 7): exposed to the program, recorded in every result. */
export const SDK_VERSION = '1.0.0';

const HINT_RULES = 'behavior-rules';
const SUMMARY_PAGE = 200;

interface Doc {
  pack: string;
  path: string;
  directory: string;
  /** The content type name. */
  kind: string;
  /** The resource's own id, the key of its tree. */
  id: string;
  created: boolean;
  deleted: boolean;
  /** The stored revision, once read (existing documents). */
  rev?: number;
  /** Rules the document holds: those it had, and those this program adds. */
  rules: number;
  loaded: boolean;
}

type Dict = Record<string, unknown>;

interface Parsed {
  packs: string[];
  builtin: string[];
}

export function createSession(config: SessionConfig): Session {
  const { surface, port } = config;
  const limits: Limits = { ...DEFAULT_LIMITS, ...config.limits };
  const programFile = config.programFile;
  const capabilities = surface.capabilities;
  const writable = capabilityOn(capabilities, CAPABILITY_TRANSACTION);
  const withLogic = capabilityOn(capabilities, CAPABILITY_LOGIC);

  // ---- the run's state ----------------------------------------------------------------------------

  const diagnostics: SdkDiagnostic[] = [];
  const late: SdkDiagnostic[] = [];
  const operations: StagedOperation[] = [];
  const sources: (SourcePosition | null)[] = [];
  const documents = new Set<string>();
  const logs: string[] = [];
  const assetRefs: { path: string; source: SourcePosition | undefined }[] = [];
  const created = new Map<string, Handle>();
  const existing = new Map<string, Doc>();
  const readCache = new Map<string, unknown>();
  let logged = 0;
  let logTruncated = false;
  let reads = 0;
  let readBytes = 0;
  let summaryText: string | null = null;
  let sessionPack: string | undefined;
  let packs: Parsed | undefined;
  let closed = false;
  let result: ProgramResult | undefined;

  type Handle = Record<string, unknown>;

  // ---- diagnostics --------------------------------------------------------------------------------

  /** The program's frame in the current stack: where in the program the call being served was made. */
  function locate(): SourcePosition | undefined {
    if (programFile === undefined) return undefined;
    const holder = Error as unknown as { stackTraceLimit?: number };
    const previous = holder.stackTraceLimit;
    try {
      holder.stackTraceLimit = 64;
    } catch {
      /* an engine that does not let the limit change keeps its own */
    }
    let stack: string | undefined;
    try {
      stack = new Error().stack;
    } finally {
      try {
        holder.stackTraceLimit = previous;
      } catch {
        /* as above */
      }
    }
    return positionIn(stack, programFile);
  }

  function diagnostic(code: SdkCode, message: string, extra?: { hint?: string; serverCode?: string; source?: SourcePosition }): SdkDiagnostic {
    const found: SdkDiagnostic = { code, severity: SDK_CODES[code].severity, message };
    if (extra?.hint !== undefined) found.hint = extra.hint;
    const source = extra?.source ?? locate();
    if (source !== undefined) found.source = source;
    if (extra?.serverCode !== undefined) found.serverCode = extra.serverCode;
    return found;
  }

  /** Records an error and ends the run: the diagnostic is recorded before the throw, so catching the throw does not hide it. */
  function fail(code: SdkCode, message: string, extra?: { hint?: string; serverCode?: string }): never {
    const found = diagnostic(code, message, extra);
    (closed ? late : diagnostics).push(found);
    throw new SdkError(found);
  }

  function warn(code: SdkCode, message: string): void {
    diagnostics.push(diagnostic(code, message));
  }

  function open(): void {
    if (closed) fail('SDK-0003', 'the run has ended: nothing can be read or staged after the program returns', { hint: 'a program runs to its end once; do not keep handles for later' });
  }

  // ---- reads --------------------------------------------------------------------------------------

  function stable(value: unknown): string {
    return JSON.stringify(value);
  }

  function exposed(entry: OpEntry, what: string): void {
    if (entry.capability !== undefined && !capabilityOn(capabilities, entry.capability)) {
      fail('SDK-0006', `${what} needs the "${entry.capability}" capability, which the connected server does not advertise`);
    }
  }

  /** One allowlisted read through the port, counted against the budget, cached for the run. */
  function read(sdk: string, payload: Dict): Dict {
    open();
    const entry = readEntry(sdk);
    exposed(entry, sdk);
    const key = `${entry.op}\u0000${stable(payload)}`;
    const cached = readCache.get(key);
    if (cached !== undefined) return cached as Dict;
    if (reads >= limits.reads) fail('SDK-0005', `the program made more than ${limits.reads} reads`, { hint: 'read a list once and filter it in the program' });
    reads += 1;
    const reply = port.read(entry.op, payload);
    if (!reply.ok) {
      fail('SDK-0010', `${entry.op}: ${reply.error.message}`, reply.error.code === undefined ? undefined : { serverCode: reply.error.code });
    }
    const text = stable(reply.data);
    readBytes += byteLength(text);
    if (readBytes > limits.readBytes) fail('SDK-0005', `the reads returned more than ${limits.readBytes} bytes`, { hint: 'ask for fewer resources: filter by type or query' });
    const data = (typeof reply.data === 'object' && reply.data !== null ? JSON.parse(text) : {}) as Dict;
    readCache.set(key, data);
    return data;
  }

  function strings(value: unknown): string[] {
    return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
  }

  function loadPacks(sdk: string): Parsed {
    if (packs === undefined) {
      const data = read(sdk, {});
      packs = { packs: strings(data.packs), builtin: strings(data.builtin) };
    }
    return packs;
  }

  // ---- the registry's layout ----------------------------------------------------------------------

  const types = sortedBy(surface.contentTypes, (type) => type.name);
  const typeByName = new Map(types.map((type) => [type.name, type]));
  const typeByDirectory = new Map(types.map((type) => [type.directory ?? type.name, type]));
  const directoryOf = (type: SurfaceContentType): string => type.directory ?? type.name;
  const rulesKeyOf = (type: SurfaceContentType): string | undefined => type.parameters.find((param) => param.uiHint === HINT_RULES)?.id;

  function memberMap(entries: readonly { id: string }[], reserved?: ReadonlySet<string>): Map<string, string> {
    return memberNames(entries.map((entry) => entry.id), reserved);
  }

  // ---- source positions ---------------------------------------------------------------------------

  function thrownMessage(thrown: unknown): string {
    if (typeof thrown === 'string') return thrown;
    if (typeof thrown === 'object' && thrown !== null) {
      const error = thrown as { name?: unknown; message?: unknown };
      if (typeof error.message === 'string') return typeof error.name === 'string' ? `${error.name}: ${error.message}` : error.message;
    }
    try {
      return JSON.stringify(thrown) ?? String(thrown);
    } catch {
      return String(thrown);
    }
  }

  // ---- values -------------------------------------------------------------------------------------

  /** A value as a document tree (JSON), or the diagnostic that says why it cannot be one. */
  function node(value: unknown, where: string): JsonValue {
    const info = tagOf(value);
    if (info !== undefined) {
      switch (info.tag) {
        case 'node':
          return info.node;
        case 'expr':
          return `\${${info.source}}`;
        case 'context':
          return `\${${info.path}}`;
        case 'ref':
          return info.id;
        default:
          return fail('SDK-0009', `${where}: this value (${info.tag}) is not something a document holds`, { hint: 'a trigger goes first in rules.add, a recipe goes to rules.instantiate' });
      }
    }
    if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
    if (typeof value === 'number') {
      if (!Number.isFinite(value)) fail('SDK-0009', `${where}: ${String(value)} cannot be written to a document`);
      return value;
    }
    if (Array.isArray(value)) {
      return value.map((entry, index) => {
        if (entry === undefined) fail('SDK-0009', `${where}[${index}]: an undefined entry cannot be written to a document`);
        return node(entry, `${where}[${index}]`);
      });
    }
    if (isPlain(value)) {
      const out: Record<string, JsonValue> = {};
      for (const key of Object.keys(value)) {
        const entry = value[key];
        if (entry !== undefined) out[key] = node(entry, `${where}.${key}`);
      }
      return out;
    }
    return fail('SDK-0009', `${where}: a ${typeof value === 'object' ? 'non-plain object' : typeof value} cannot be written to a document`, { hint: 'documents hold text, numbers, booleans, lists and mappings' });
  }

  function steps(value: unknown, where: string): JsonValue[] {
    if (!Array.isArray(value)) return fail('SDK-0012', `${where}: a list of steps is expected`);
    return value.map((entry, index) => {
      const info = tagOf(entry);
      if (info === undefined || info.tag !== 'node') return fail('SDK-0012', `${where}[${index}]: not a step; build steps with m.actions and m.steps`);
      return info.node;
    });
  }

  function condition(value: unknown, where: string): JsonValue {
    const info = tagOf(value);
    if (info !== undefined && (info.tag === 'node' || info.tag === 'expr')) return node(value, where);
    return fail('SDK-0012', `${where}: not a condition; build one with m.conditions or m.expr`);
  }

  /** The text an expression gets for an operand. */
  function operand(value: unknown, where: string): string {
    const info = tagOf(value);
    if (info !== undefined) {
      if (info.tag === 'expr') return info.source;
      if (info.tag === 'context') return info.path;
      if (info.tag === 'ref') return info.id;
      return fail('SDK-0009', `${where}: this value cannot be part of an expression`);
    }
    if (typeof value === 'string') return `'${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
    if (typeof value === 'boolean') return String(value);
    return fail('SDK-0009', `${where}: only text, numbers, booleans, expressions, context values and references go into an expression`);
  }

  function wrapNode(value: JsonValue): unknown {
    return tagged({ __node: true }, { tag: 'node', node: value });
  }

  // ---- builders: one per registry entry, generic --------------------------------------------------

  function isBare(value: unknown): boolean {
    return value !== undefined && !isPlain(value);
  }

  function checkKeys(owner: string, params: Dict, allowed: ReadonlySet<string>): void {
    for (const key of Object.keys(params)) {
      if (params[key] === undefined || allowed.has(key)) continue;
      fail('SDK-0012', `${owner} has no parameter "${key}"`, { hint: `it takes: ${[...allowed].sort().join(', ') || 'nothing'}` });
    }
  }

  function paramIds(entry: { parameters: readonly SurfaceParam[] }): Set<string> {
    return new Set(entry.parameters.map((param) => param.id));
  }

  function noteUnavailable(owner: string, entry: { available?: boolean; unavailableReason?: string }): void {
    if (entry.available === false) warn('SDK-0016', `${owner} is not available on the connected server: ${entry.unavailableReason ?? 'no reason given'}`);
  }

  function actionBuilders(): Dict {
    const optionIds = new Set(surface.behavior.actionOptions.map((option) => option.id));
    const names = memberMap(surface.behavior.actions);
    const catalog: Dict = {};
    for (const action of sortedBy(surface.behavior.actions, (entry) => entry.id)) {
      const member = names.get(action.id) ?? camel(action.id);
      const own = paramIds(action);
      const allowed = new Set([...own, ...optionIds]);
      catalog[member] = (params?: unknown, options?: unknown): unknown => {
        const owner = `m.actions.${member}`;
        noteUnavailable(owner, action);
        const step: Dict = {};
        let body: unknown = params;
        let extra: Dict = {};
        if (options !== undefined) {
          if (!isPlain(options)) fail('SDK-0012', `${owner}: options must be a mapping`);
          checkKeys(`${owner} options`, options as Dict, optionIds);
          extra = { ...(options as Dict) };
        }
        if (isBare(params)) {
          if (action.shorthand === undefined) fail('SDK-0012', `${owner} takes a mapping of parameters, not a bare value`);
        } else if (isPlain(params)) {
          checkKeys(owner, params as Dict, allowed);
          const own: Dict = {};
          for (const key of Object.keys(params as Dict)) {
            const entry = (params as Dict)[key];
            if (entry === undefined) continue;
            if (optionIds.has(key)) extra[key] = entry;
            else own[key] = entry;
          }
          body = Object.keys(own).length === 0 ? undefined : own;
        } else if (params !== undefined) {
          fail('SDK-0012', `${owner}: parameters must be a mapping or the one bare value`);
        }
        if (body === undefined && Object.keys(extra).length === 0) return wrapNode(action.id);
        step[action.id] = body === undefined ? {} : node(body, `${owner} parameters`);
        for (const key of Object.keys(extra)) step[key] = node(extra[key], `${owner} ${key}`);
        return wrapNode(step as JsonValue);
      };
    }
    return catalog;
  }

  /** Conditions, sources, mechanics: `{ id: bare-or-mapping }`, the shape a document writes them in. */
  function entryBuilders(
    entries: readonly (SurfaceEntry & { shorthand?: string; available?: boolean; unavailableReason?: string })[],
    ownerPrefix: string,
    emptyAsMapping: boolean,
    asType?: boolean,
  ): Dict {
    const names = memberMap(entries);
    const catalog: Dict = {};
    for (const entry of sortedBy(entries, (candidate) => candidate.id)) {
      const member = names.get(entry.id) ?? camel(entry.id);
      const allowed = paramIds(entry);
      catalog[member] = (params?: unknown): unknown => {
        const owner = `${ownerPrefix}.${member}`;
        noteUnavailable(owner, entry);
        let body: JsonValue;
        if (isBare(params)) {
          if (entry.shorthand === undefined) fail('SDK-0012', `${owner} takes a mapping of parameters, not a bare value`);
          body = node(params, owner);
        } else if (params === undefined) {
          body = {};
        } else if (isPlain(params)) {
          checkKeys(owner, params as Dict, allowed);
          body = node(params, owner);
        } else {
          return fail('SDK-0012', `${owner}: parameters must be a mapping or the one bare value`);
        }
        if (asType === true) {
          return wrapNode({ type: entry.id, ...(body as Record<string, JsonValue>) });
        }
        if (params === undefined && !emptyAsMapping) return wrapNode(entry.id);
        return wrapNode({ [entry.id]: body });
      };
    }
    return catalog;
  }

  function layerBuilders(): Dict {
    const names = memberMap(surface.gui.components);
    const catalog: Dict = {};
    for (const component of sortedBy(surface.gui.components, (entry) => entry.id)) {
      const member = names.get(component.id) ?? camel(component.id);
      catalog[member] = (params?: unknown): unknown => {
        const owner = `m.guiComponents.${member}`;
        if (!isPlain(params)) fail('SDK-0012', `${owner} takes a mapping of parameters`);
        // a layer's common properties (id, geometry, visibility) are the GUI type's own schema, not the component's
        return wrapNode({ type: component.id, ...(node(params, owner) as Record<string, JsonValue>) });
      };
    }
    return catalog;
  }

  function triggerBuilders(): Dict {
    const names = memberMap(surface.behavior.triggers);
    const catalog: Dict = {};
    for (const trigger of sortedBy(surface.behavior.triggers, (entry) => entry.id)) {
      const member = names.get(trigger.id) ?? camel(trigger.id);
      const filterIds = new Set(trigger.filters.map((filter) => filter.id));
      catalog[member] = (params?: unknown): unknown => {
        const owner = `m.triggers.${member}`;
        const filters: [string, JsonValue][] = [];
        let detail: string | null = null;
        if (params !== undefined) {
          if (!isPlain(params)) fail('SDK-0012', `${owner} takes a mapping of filters`);
          const given = params as Dict;
          for (const key of Object.keys(given)) {
            const value = given[key];
            if (value === undefined) continue;
            if (key === 'detail') {
              if (typeof value !== 'string' || !trigger.details.includes(value)) {
                fail('SDK-0012', `${owner}: detail must be one of ${trigger.details.join(', ') || '(none: this trigger has no variants)'}`);
              }
              detail = value as string;
              continue;
            }
            if (!filterIds.has(key)) fail('SDK-0012', `${owner} has no filter "${key}"`, { hint: `it filters on: ${[...filterIds].sort().join(', ') || 'nothing'}` });
            filters.push([key, node(value, `${owner} ${key}`)]);
          }
        }
        return tagged({ __trigger: true, id: trigger.id }, { tag: 'trigger', id: trigger.id, detail, filters });
      };
    }
    return catalog;
  }

  function recipeBuilders(): Dict {
    const names = memberMap(surface.behavior.recipes);
    const catalog: Dict = {};
    for (const recipe of sortedBy(surface.behavior.recipes, (entry) => entry.id)) {
      const member = names.get(recipe.id) ?? camel(recipe.id);
      const allowed = new Set(recipe.params.map((param) => param.name));
      catalog[member] = (params?: unknown): unknown => {
        const owner = `m.recipes.${member}`;
        if (params !== undefined && !isPlain(params)) fail('SDK-0012', `${owner} takes a mapping of parameters`);
        checkKeys(owner, (params ?? {}) as Dict, allowed);
        return tagged({ __recipe: true, id: recipe.id }, { tag: 'recipeInstance', id: recipe.id, params: node(params ?? {}, owner) });
      };
    }
    return catalog;
  }

  function functionBuilders(): Dict {
    const names = memberMap(surface.behavior.functions);
    const catalog: Dict = {};
    for (const fn of sortedBy(surface.behavior.functions, (entry) => entry.id)) {
      const member = names.get(fn.id) ?? camel(fn.id);
      const [min, max] = arity(fn);
      catalog[member] = (...args: unknown[]): unknown => {
        const owner = `m.fn.${member}`;
        if (args.length < min || args.length > max) {
          fail('SDK-0012', `${owner} takes ${min === max ? String(min) : max === Infinity ? `at least ${min}` : `${min} to ${max}`} arguments, got ${args.length}`);
        }
        const text = `${fn.id}(${args.map((arg, index) => operand(arg, `${owner} argument ${index + 1}`)).join(', ')})`;
        return tagged({ __expr: true, source: text }, { tag: 'expr', source: text });
      };
    }
    return catalog;
  }

  function arity(fn: SurfaceFunction): [number, number] {
    if (fn.parameters.length === 0) return [0, fn.maxArguments === 0 ? 0 : Infinity];
    return [fn.minArguments, fn.variadic ? Infinity : fn.parameters.length];
  }

  // ---- contexts -----------------------------------------------------------------------------------

  function contextValue(path: string): unknown {
    return tagged({ path }, { tag: 'context', path });
  }

  function contextObject(n: ContextNode, prefix: string): unknown {
    const target: Dict = {};
    if (n.leaf !== undefined) target.path = prefix;
    for (const [name, child] of n.children) {
      const childPath = prefix === '' ? name : `${prefix}.${name}`;
      target[name] = contextObject(child, childPath);
    }
    const base = n.leaf !== undefined ? tag(target, { tag: 'context', path: prefix }) : target;
    if (n.wildcard === undefined) return Object.freeze(base);
    const family = prefix === '' ? '' : `${prefix}.`;
    return new Proxy(base, {
      get(object, key) {
        if (typeof key === 'string' && !(key in object)) return contextValue(`${family}${key}`);
        return (object as Dict)[key as string];
      },
    });
  }

  const contexts = new Map<string, unknown>();
  function contextOf(trigger: SurfaceTrigger, host: string): unknown {
    const key = `${trigger.id}\u0000${host}`;
    let context = contexts.get(key);
    if (context === undefined) {
      context = contextObject(contextTree(contextFor(trigger.context, host)), '');
      contexts.set(key, context);
    }
    return context;
  }

  const triggerById = new Map(surface.behavior.triggers.map((trigger) => [trigger.id, trigger]));
  const ruleOptionIds = new Set(surface.behavior.ruleOptions.map((option) => option.id));

  // ---- staging ------------------------------------------------------------------------------------

  function claimPack(pack: string): void {
    if (sessionPack === pack) return;
    if (sessionPack !== undefined) {
      fail('SDK-0004', `this program writes to pack "${sessionPack}" and now to "${pack}": a program is one transaction in one pack`, { hint: 'split the change into two programs, one per pack' });
    }
    const known = loadPacks('m.pack');
    if (known.builtin.includes(pack)) fail('SDK-0008', `pack "${pack}" is built in and read-only`, { hint: 'built-in content can be read and referenced, never written' });
    if (!known.packs.includes(pack)) {
      const close = nearest(pack, known.packs);
      fail('SDK-0008', `no pack "${pack}"`, { hint: close.length > 0 ? `nearest packs: ${close.join(', ')}` : 'the server has no packs yet' });
    }
    sessionPack = pack;
  }

  /** Records one operation. `touched` are the documents it changes; `edits` the entries it carries. */
  function stage(sdk: string, op: StagedOperation, touched: readonly string[], edits: number): void {
    open();
    const entry = writeEntry(sdk, op.kind);
    exposed(entry, sdk);
    if (operations.length >= limits.operations) fail('SDK-0005', `the program staged more than ${limits.operations} operations`, { hint: 'a transaction is at most that long; split the work into several programs' });
    if (edits > limits.editsPerOperation) fail('SDK-0005', `one operation carries ${edits} edits; the most is ${limits.editsPerOperation}`);
    const next = new Set(documents);
    for (const path of touched) next.add(path);
    if (next.size > limits.documents) fail('SDK-0005', `the program touches more than ${limits.documents} documents`, { hint: 'a transaction is at most that wide; split the work into several programs' });
    const source = locate();
    operations.push(op);
    sources.push(source ?? null);
    for (const path of touched) documents.add(path);
    port.stage?.(op, source ?? null, operations.length - 1);
  }

  function fieldEdits(doc: Doc, fields: unknown, owner: string): JsonValue[] {
    if (!isPlain(fields)) return fail('SDK-0012', `${owner}: the fields must be a mapping`);
    const edits: JsonValue[] = [];
    for (const key of Object.keys(fields)) {
      const value = (fields as Dict)[key];
      if (value === undefined) continue;
      edits.push({ field: [doc.id, key], value: node(value, `${owner}.${key}`) });
    }
    return edits;
  }

  /** Reads an existing document once: its revision, and how many rules it holds. */
  function load(doc: Doc): void {
    if (doc.created || doc.loaded) return;
    const data = read('m.edit', { pack: doc.pack, path: doc.path });
    if (typeof data.rev !== 'number') fail('SDK-0010', `browse.get answered without a revision for ${doc.path}`);
    doc.rev = data.rev as number;
    const type = typeByName.get(doc.kind);
    const key = type === undefined ? undefined : rulesKeyOf(type);
    const parsed = isPlain(data.parsed) ? ((data.parsed as Dict)[doc.id] as Dict | undefined) : undefined;
    const held = key !== undefined && parsed !== undefined && isPlain(parsed) ? (parsed as Dict)[key] : undefined;
    doc.rules = Array.isArray(held) ? held.length : 0;
    doc.loaded = true;
  }

  /** Where an operation on `doc` starts: the checks, then the `rev` of a stored document. */
  function prepare(doc: Doc, owner: string): { rev?: number } {
    open();
    if (doc.deleted) fail('SDK-0012', `${owner}: ${doc.path} is deleted by this program`);
    claimPack(doc.pack);
    load(doc);
    return doc.created || doc.rev === undefined ? {} : { rev: doc.rev };
  }

  // ---- handles ------------------------------------------------------------------------------------

  function checkId(id: unknown, owner: string): string {
    if (!validId(id)) fail('SDK-0012', `${owner}: an id is 2 to 64 characters of a-z, 0-9, _ and -`, { hint: `got ${JSON.stringify(id)}` });
    return id as string;
  }

  function newDocument(pack: string, type: SurfaceContentType, id: string, folder: unknown, owner: string): Doc {
    if (folder !== undefined && typeof folder !== 'string') fail('SDK-0012', `${owner}: folder must be text`);
    const directory = directoryOf(type);
    const path = documentPath(directory, folder as string | undefined, id);
    if (path === null) return fail('SDK-0012', `${owner}: "${String(folder)}" is not a folder the server accepts`, { hint: 'letters, digits, spaces, _ and -, separated by /' });
    const key = `${type.name}\u0000${pack}:${id}`;
    if (created.has(key)) fail('SDK-0013', `${type.name} "${pack}:${id}" is already staged by this program`);
    for (const other of created.values()) {
      const doc = docs.get(other);
      if (doc !== undefined && doc.path === path && doc.pack === pack) fail('SDK-0013', `${path} is already staged by this program`);
    }
    return { pack, path, directory, kind: type.name, id, created: true, deleted: false, rules: 0, loaded: true };
  }

  const docs = new WeakMap<object, Doc>();

  function ruleSetOf(doc: Doc): Dict {
    return Object.freeze({
      add(trigger: unknown, build: unknown): unknown {
        const owner = `${doc.kind} "${doc.pack}:${doc.id}" rules.add`;
        open();
        const info = tagOf(trigger);
        if (info === undefined || info.tag !== 'trigger') return fail('SDK-0012', `${owner}: the first argument is a trigger built with m.triggers`);
        const registered = triggerById.get(info.id);
        if (registered === undefined) return fail('SDK-0012', `${owner}: unknown trigger ${info.id}`);
        if (!registered.firedBy.includes(doc.kind)) {
          return fail('SDK-0012', `${owner}: "${info.id}" is not fired by ${doc.kind}`, { hint: `it is fired by: ${registered.firedBy.join(', ') || 'nothing yet'}` });
        }
        if (typeof build !== 'function') return fail('SDK-0012', `${owner}: the second argument is a function that returns the rule`);
        const body = (build as (context: unknown) => unknown)(contextOf(registered, doc.kind));
        if (!isPlain(body)) return fail('SDK-0012', `${owner}: the function returns a mapping with a "do" list`);
        const given = body as Dict;
        if (!Array.isArray(given[FLOW.body])) fail('SDK-0012', `${owner}: the rule needs a "${FLOW.body}" list of steps`);
        const rule: Record<string, JsonValue> = { on: info.detail === null ? info.id : `${info.id}:${info.detail}` };
        for (const [key, value] of info.filters) rule[key] = value;
        for (const key of Object.keys(given)) {
          if (key === FLOW.body || key === FLOW.condition || given[key] === undefined) continue;
          if (!ruleOptionIds.has(key)) fail('SDK-0012', `${owner}: a rule has no option "${key}"`, { hint: `options: ${[...ruleOptionIds].sort().join(', ')}` });
          rule[key] = node(given[key], `${owner} ${key}`);
        }
        if (given[FLOW.condition] !== undefined) rule[FLOW.condition] = condition(given[FLOW.condition], `${owner} ${FLOW.condition}`);
        rule[FLOW.body] = steps(given[FLOW.body], `${owner} ${FLOW.body}`);
        const start = prepare(doc, owner);
        const index = doc.rules;
        stage('rules.add', { kind: 'logicEdit', path: doc.path, ...start, edits: [{ op: 'addRule', index, rule }] }, [doc.path], 1);
        doc.rules += 1;
        return tagged({ __rule: true }, { tag: 'ruleHandle' });
      },
      instantiate(recipe: unknown): unknown {
        const owner = `${doc.kind} "${doc.pack}:${doc.id}" rules.instantiate`;
        open();
        const info = tagOf(recipe);
        if (info === undefined || info.tag !== 'recipeInstance') return fail('SDK-0012', `${owner}: the argument is a recipe built with m.recipes`);
        const start = prepare(doc, owner);
        const data = read('rules.instantiate', { recipe: info.id, params: info.params });
        for (const item of Array.isArray(data.warnings) ? data.warnings : []) {
          const text = isPlain(item) && typeof (item as Dict).message === 'string' ? ((item as Dict).message as string) : JSON.stringify(item);
          warn('SDK-0017', `recipe ${info.id}: ${text}`);
        }
        const rules = Array.isArray(data.rules) ? data.rules : [];
        if (rules.length === 0) fail('SDK-0010', `recipe ${info.id} expanded to no rules`);
        const edits: JsonValue[] = rules.map((rule, offset) => ({ op: 'addRule', index: doc.rules + offset, rule: rule as JsonValue }));
        stage('rules.instantiate', { kind: 'logicEdit', path: doc.path, ...start, edits }, [doc.path], edits.length);
        doc.rules += rules.length;
        return tagged({ __rule: true }, { tag: 'ruleHandle' });
      },
    });
  }

  function makeHandle(doc: Doc, type: SurfaceContentType): Handle {
    const id = `${doc.pack}:${doc.id}`;
    const handle: Handle = { kind: type.name, id };
    const label = `${type.name} "${id}"`;
    handle.set = (fields: unknown): void => {
      const owner = `${label} set`;
      const edits = fieldEdits(doc, fields, owner);
      if (edits.length === 0) fail('SDK-0012', `${owner}: no field to set`);
      const start = prepare(doc, owner);
      stage('handle.set', { kind: 'setFields', path: doc.path, ...start, edits }, [doc.path], edits.length);
    };
    handle.unset = (...keys: unknown[]): void => {
      const owner = `${label} unset`;
      if (keys.length === 0 || keys.some((key) => typeof key !== 'string')) fail('SDK-0012', `${owner}: name the fields to remove`);
      const start = prepare(doc, owner);
      const edits = (keys as string[]).map((key) => ({ field: [doc.id, key], remove: true }));
      stage('handle.unset', { kind: 'setFields', path: doc.path, ...start, edits }, [doc.path], edits.length);
    };
    handle.duplicate = (newId: unknown, options?: unknown): unknown => {
      const owner = `${label} duplicate`;
      const given = options === undefined ? {} : isPlain(options) ? (options as Dict) : fail('SDK-0012', `${owner}: options must be a mapping`);
      const copy = newDocument(doc.pack, type, checkId(newId, owner), given.folder, owner);
      const edits = given.fields === undefined ? [] : fieldEdits(copy, given.fields, owner);
      const start = prepare(doc, owner);
      const op: StagedOperation = { kind: 'duplicate', fromPath: doc.path, ...start, id: copy.id };
      if (typeof given.folder === 'string') op.folder = given.folder;
      if (edits.length > 0) op.edits = edits;
      stage('handle.duplicate', op, [copy.path], edits.length);
      return register(copy, type);
    };
    handle.delete = (): void => {
      const owner = `${label} delete`;
      if (doc.created) fail('SDK-0012', `${owner}: this program creates it; do not create it instead`);
      const start = prepare(doc, owner);
      stage('handle.delete', { kind: 'delete', path: doc.path, ...start }, [doc.path], 0);
      doc.deleted = true;
    };
    handle.detachTemplate = (): void => {
      const owner = `${label} detachTemplate`;
      const start = prepare(doc, owner);
      stage('handle.detachTemplate', { kind: 'detachTemplate', path: doc.path, ...start, resource: doc.id }, [doc.path], 0);
    };
    handle.saveAsTemplate = (templateId: unknown, fields: unknown): void => {
      const owner = `${label} saveAsTemplate`;
      if (typeof templateId !== 'string' || templateId === '') fail('SDK-0012', `${owner}: name the template`);
      if (!Array.isArray(fields) || fields.length === 0 || fields.some((field) => typeof field !== 'string')) {
        fail('SDK-0012', `${owner}: choose at least one field to put in the template`);
      }
      const start = prepare(doc, owner);
      stage('handle.saveAsTemplate', { kind: 'saveAsTemplate', fromPath: doc.path, ...start, resource: doc.id, templateId: templateId as string, fields: fields as string[] }, [`templates/${templateId as string}.yml`], (fields as unknown[]).length);
    };
    if (withLogic && rulesKeyOf(type) !== undefined) handle.rules = ruleSetOf(doc);
    return tagged(handle, { tag: 'ref', id, kind: type.name });
  }

  function register(doc: Doc, type: SurfaceContentType): Handle {
    const handle = makeHandle(doc, type);
    docs.set(handle, doc);
    created.set(`${type.name}\u0000${doc.pack}:${doc.id}`, handle);
    return handle;
  }

  // ---- resolving references to stored resources ---------------------------------------------------

  function splitRef(id: unknown, owner: string): { pack: string; local: string } {
    if (typeof id !== 'string') return fail('SDK-0012', `${owner}: a reference is the text "pack:id"`);
    const at = id.indexOf(':');
    if (at <= 0 || at === id.length - 1) return fail('SDK-0012', `${owner}: a reference is "pack:id", got "${id}"`);
    return { pack: id.slice(0, at), local: id.slice(at + 1) };
  }

  function summaryDoc(row: unknown, type: SurfaceContentType | undefined, pack: string): Doc | undefined {
    if (!isPlain(row)) return undefined;
    const r = row as Dict;
    if (typeof r.id !== 'string' || typeof r.path !== 'string' || typeof r.type !== 'string') return undefined;
    const known = typeByDirectory.get(r.type);
    const kind = type?.name ?? known?.name ?? r.type;
    return { pack: typeof r.pack === 'string' ? r.pack : pack, path: r.path, directory: r.type, kind, id: r.id, created: false, deleted: false, rules: 0, loaded: false };
  }

  /** The stored resource `pack:id` of `type`, or SDK-0007 naming the nearest ids. */
  function resolve(type: SurfaceContentType, id: unknown, owner: string): Doc {
    const { pack, local } = splitRef(id, owner);
    const refId = `${pack}:${local}`;
    const known = existing.get(`${type.name}\u0000${refId}`);
    if (known !== undefined) return known;
    const directory = directoryOf(type);
    const exact = read('m.ref', { pack, type: directory, query: local, limit: SUMMARY_PAGE });
    for (const row of Array.isArray(exact.resources) ? exact.resources : []) {
      const doc = summaryDoc(row, type, pack);
      if (doc !== undefined && doc.id === local) {
        existing.set(`${type.name}\u0000${refId}`, doc);
        return doc;
      }
    }
    const everything = read('m.ref', { pack, type: directory, limit: SUMMARY_PAGE });
    const ids = (Array.isArray(everything.resources) ? everything.resources : [])
      .map((row) => (isPlain(row) && typeof (row as Dict).id === 'string' ? `${pack}:${(row as Dict).id as string}` : ''))
      .filter((candidate) => candidate !== '');
    const close = nearest(refId, ids);
    return fail('SDK-0007', `no ${type.name} "${refId}"`, { hint: close.length > 0 ? `nearest ${type.name} ids: ${close.join(', ')}` : `pack "${pack}" has no ${type.name} yet` });
  }

  function refValue(kind: string, id: string): unknown {
    return tagged({ kind, id }, { tag: 'ref', id, kind });
  }

  function refFactory(): Dict {
    const names = refMembers(types);
    const factory: Dict = {};
    for (const type of types) {
      const member = names.get(type.name) ?? camel(type.name);
      factory[member] = (id: unknown): unknown => {
        const owner = `m.ref.${member}`;
        const { pack, local } = splitRef(id, owner);
        const staged = created.get(`${type.name}\u0000${pack}:${local}`);
        if (staged !== undefined) return staged;
        return refValue(type.name, resolve(type, id, owner).pack + ':' + local);
      };
    }
    factory.asset = (path: unknown): unknown => {
      if (typeof path !== 'string' || path === '') fail('SDK-0012', 'm.ref.asset: an asset is the path the owner uploaded it to');
      assetRefs.push({ path: path as string, source: locate() });
      return refValue('asset', path as string);
    };
    return factory;
  }

  // ---- the writer and the editor of an existing resource ------------------------------------------

  function packWriter(pack: string): Dict {
    const writer: Dict = { id: pack };
    if (!writable) return writer;
    const names = writerMembers(types);
    for (const type of types) {
      const member = names.get(directoryOf(type)) ?? camel(directoryOf(type));
      writer[member] = Object.freeze({
        create(id: unknown, fields: unknown, options?: unknown): unknown {
          const owner = `${member}.create`;
          open();
          const given = options === undefined ? {} : isPlain(options) ? (options as Dict) : fail('SDK-0012', `${owner}: options must be a mapping`);
          const checked = checkId(id, owner);
          const doc = newDocument(pack, type, checked, given.folder, owner);
          const edits = fieldEdits(doc, fields === undefined ? {} : fields, owner);
          claimPack(pack);
          const op: StagedOperation = { kind: 'create', type: doc.directory, id: checked };
          if (typeof given.folder === 'string') op.folder = given.folder;
          if (edits.length > 0) op.edits = edits;
          stage('collection.create', op, [doc.path], edits.length);
          return register(doc, type);
        },
      });
    }
    return Object.freeze(writer);
  }

  const handleOf = (ref: unknown): { doc: Doc; type: SurfaceContentType } | undefined => {
    const info = tagOf(ref);
    if (info === undefined || info.tag !== 'ref') return undefined;
    const type = typeByName.get(info.kind);
    if (type === undefined) return undefined;
    const { pack, local } = splitRef(info.id, 'm.edit');
    const staged = created.get(`${type.name}\u0000${pack}:${local}`);
    if (staged !== undefined) {
      const doc = docs.get(staged);
      return doc === undefined ? undefined : { doc, type };
    }
    return { doc: resolve(type, info.id, 'm.edit'), type };
  };

  const edited = new Map<Doc, Handle>();
  function edit(ref: unknown): unknown {
    open();
    if (!writable) fail('SDK-0006', `m.edit needs the "${CAPABILITY_TRANSACTION}" capability, which the connected server does not advertise`);
    const found = handleOf(ref);
    if (found === undefined) return fail('SDK-0012', 'm.edit takes a reference to a resource: m.ref.<type>("pack:id")');
    const staged = created.get(`${found.type.name}\u0000${found.doc.pack}:${found.doc.id}`);
    if (staged !== undefined) return staged;
    let handle = edited.get(found.doc);
    if (handle === undefined) {
      handle = makeHandle(found.doc, found.type);
      edited.set(found.doc, handle);
    }
    return handle;
  }

  // ---- the read side ------------------------------------------------------------------------------

  function summaryRef(doc: Doc): unknown {
    existing.set(`${doc.kind}\u0000${doc.pack}:${doc.id}`, doc);
    return refValue(doc.kind, `${doc.pack}:${doc.id}`);
  }

  function readApi(): Dict {
    return Object.freeze({
      packs(): unknown {
        const known = loadPacks('m.read.packs');
        return [...known.packs.map((id) => ({ id, builtin: false })), ...known.builtin.map((id) => ({ id, builtin: true }))];
      },
      resources(options: unknown): unknown {
        const owner = 'm.read.resources';
        if (!isPlain(options) || typeof (options as Dict).pack !== 'string') return fail('SDK-0012', `${owner}: pass { pack }`);
        const o = options as Dict;
        let directory: string | undefined;
        if (o.type !== undefined) {
          const type = typeof o.type === 'string' ? typeByName.get(o.type) : undefined;
          if (type === undefined) return fail('SDK-0012', `${owner}: no content type "${String(o.type)}"`, { hint: `types: ${types.map((entry) => entry.name).join(', ')}` });
          directory = directoryOf(type);
        }
        const want = typeof o.limit === 'number' && o.limit > 0 ? Math.min(Math.floor(o.limit), limits.resources) : limits.resources;
        const rows: unknown[] = [];
        let offset = 0;
        for (;;) {
          const page = Math.min(SUMMARY_PAGE, want - rows.length);
          const payload: Dict = { pack: o.pack };
          if (directory !== undefined) payload.type = directory;
          if (typeof o.query === 'string') payload.query = o.query;
          if (offset > 0) payload.offset = offset;
          payload.limit = page;
          const data = read('m.read.resources', payload);
          const got = Array.isArray(data.resources) ? data.resources : [];
          for (const row of got) {
            const doc = summaryDoc(row, undefined, o.pack as string);
            if (doc === undefined) continue;
            const r = row as Dict;
            const entry: Dict = {
              ref: summaryRef(doc),
              pack: doc.pack,
              type: doc.directory,
              path: doc.path,
              id: doc.id,
              name: typeof r.name === 'string' ? r.name : doc.id,
              health: typeof r.health === 'string' ? r.health : 'unknown',
            };
            if (typeof r.healthMessage === 'string') entry.healthMessage = r.healthMessage;
            rows.push(entry);
          }
          if (rows.length >= want || typeof data.nextOffset !== 'number' || got.length === 0) break;
          offset = data.nextOffset;
        }
        return rows.slice(0, want);
      },
      get(ref: unknown): unknown {
        const found = handleOf(ref);
        if (found === undefined) return fail('SDK-0012', 'm.read.get takes a reference to a resource: m.ref.<type>("pack:id")');
        if (found.doc.created) return fail('SDK-0012', `m.read.get: "${found.doc.pack}:${found.doc.id}" is staged by this program; reads see the stored state only`);
        const data = read('m.read.get', { pack: found.doc.pack, path: found.doc.path });
        const parsed = isPlain(data.parsed) ? ((data.parsed as Dict)[found.doc.id] ?? {}) : {};
        return { ref, path: found.doc.path, revision: data.rev, fields: parsed, computed: data.computed ?? {} };
      },
      references(ref: unknown): unknown {
        const found = handleOf(ref);
        if (found === undefined) return fail('SDK-0012', 'm.read.references takes a reference to a resource: m.ref.<type>("pack:id")');
        const data = read('m.read.references', { pack: found.doc.pack, kind: 'resource', target: found.doc.id });
        const coverage = isPlain(data.coverage) ? (data.coverage as Dict) : {};
        return {
          hits: Array.isArray(data.hits) ? data.hits : [],
          coverage: {
            complete: coverage.complete === true,
            documentsScanned: typeof coverage.documentsScanned === 'number' ? coverage.documentsScanned : 0,
            notScanned: Array.isArray(coverage.notScanned) ? coverage.notScanned : [],
          },
        };
      },
      history(ref: unknown, limit?: unknown): unknown {
        const found = handleOf(ref);
        if (found === undefined) return fail('SDK-0012', 'm.read.history takes a reference to a resource: m.ref.<type>("pack:id")');
        const payload: Dict = { pack: found.doc.pack, path: found.doc.path };
        if (typeof limit === 'number') payload.limit = limit;
        const data = read('m.read.history', payload);
        return Array.isArray(data.revisions) ? data.revisions : [];
      },
      diagnostics(): unknown {
        const data = read('m.read.diagnostics', {});
        return Array.isArray(data.diagnostics) ? data.diagnostics : [];
      },
    });
  }

  // ---- docs: the registry's own text, searched in the program -------------------------------------

  interface Indexed {
    kind: string;
    id: string;
    member: string;
    name: string;
    text: string;
  }

  let index: Indexed[] | undefined;
  function indexed(): Indexed[] {
    if (index !== undefined) return index;
    const out: Indexed[] = [];
    const add = (kind: string, entries: readonly (SurfaceEntry | { id: string; displayName: string; description: string })[]): void => {
      const names = memberMap(entries);
      for (const entry of entries) out.push({ kind, id: entry.id, member: names.get(entry.id) ?? camel(entry.id), name: entry.displayName, text: entry.description });
    };
    const b = surface.behavior;
    const writerNames = writerMembers(types);
    for (const type of types) {
      out.push({ kind: 'content type', id: type.name, member: writerNames.get(directoryOf(type)) ?? directoryOf(type), name: type.displayName, text: type.description });
    }
    add('m.actions', b.actions);
    add('m.conditions', b.conditions);
    add('m.triggers', b.triggers);
    add('m.mechanics', b.mechanics);
    add('m.fn', b.functions);
    add('m.recipes', b.recipes);
    add('m.guiComponents', surface.gui.components);
    add('m.itemSources', b.itemSources);
    add('m.listSources', b.listSources);
    index = out;
    return out;
  }

  function docsApi(): Dict {
    return Object.freeze({
      type(name: unknown): unknown {
        const type = typeof name === 'string' ? typeByName.get(name) : undefined;
        if (type === undefined) {
          return fail('SDK-0012', `m.docs.type: no content type "${String(name)}"`, { hint: `nearest: ${nearest(String(name), types.map((entry) => entry.name)).join(', ')}` });
        }
        return {
          name: type.name,
          directory: directoryOf(type),
          description: type.description,
          fields: type.parameters.filter((param) => param.uiHint !== HINT_RULES).map((param) => {
            const entry: Dict = { id: param.id, type: param.type, required: param.required };
            if (param.description !== undefined) entry.description = param.description;
            if (param.allowedValues !== undefined) entry.allowedValues = [...param.allowedValues];
            if (param.referenceKind !== undefined) entry.referenceKind = param.referenceKind;
            if (param.default !== undefined) entry.default = param.default;
            return entry;
          }),
        };
      },
      search(query: unknown, limit?: unknown): unknown {
        if (typeof query !== 'string' || query.trim() === '') return fail('SDK-0012', 'm.docs.search takes the words to look for');
        const words = query.toLowerCase().split(/[^a-z0-9]+/).filter((word) => word !== '');
        const want = typeof limit === 'number' && limit > 0 ? Math.min(Math.floor(limit), 50) : 10;
        const scored: { entry: Indexed; score: number }[] = [];
        for (const entry of indexed()) {
          const id = entry.id.toLowerCase();
          const name = entry.name.toLowerCase();
          const text = entry.text.toLowerCase();
          let score = 0;
          for (const word of words) {
            if (id.indexOf(word) >= 0) score += 3;
            if (name.indexOf(word) >= 0) score += 2;
            if (text.indexOf(word) >= 0) score += 1;
          }
          if (score > 0) scored.push({ entry, score });
        }
        scored.sort((a, b) => b.score - a.score || (a.entry.kind < b.entry.kind ? -1 : a.entry.kind > b.entry.kind ? 1 : 0) || (a.entry.id < b.entry.id ? -1 : a.entry.id > b.entry.id ? 1 : 0));
        return scored.slice(0, want).map(({ entry }) => ({ kind: entry.kind, id: entry.id, member: entry.member, summary: entry.text }));
      },
    });
  }

  // ---- the program's global -----------------------------------------------------------------------

  function mm(text: unknown): unknown {
    if (typeof text !== 'string') return fail('SDK-0012', 'm.mm takes text');
    let depth = 0;
    for (let i = 0; i < text.length; i += 1) {
      const c = text.charAt(i);
      if (c === '\\') i += 1;
      else if (c === '<') depth += 1;
      else if (c === '>' && depth > 0) depth -= 1;
    }
    if (depth > 0) warn('SDK-0015', `formatted text has a "<" that is never closed: ${JSON.stringify(text)}`);
    return text;
  }

  function expr(strings: unknown, ...values: unknown[]): unknown {
    if (!Array.isArray(strings) || values.length !== strings.length - 1) fail('SDK-0012', 'm.expr is a template tag: m.expr`...`');
    const parts = strings as string[];
    let source = parts[0] ?? '';
    values.forEach((value, i) => {
      source += operand(value, `m.expr \${${i + 1}}`) + (parts[i + 1] ?? '');
    });
    return tagged({ __expr: true, source }, { tag: 'expr', source });
  }

  function summary(text: unknown): void {
    open();
    if (typeof text !== 'string' || text.trim() === '') fail('SDK-0012', 'm.summary takes one line of text');
    if ((text as string).length > limits.summaryChars) fail('SDK-0005', `the summary is longer than ${limits.summaryChars} characters`);
    summaryText = (text as string).trim();
  }

  function log(...values: unknown[]): void {
    open();
    const line = values
      .map((value) => {
        if (typeof value === 'string') return value;
        try {
          return JSON.stringify(node(value, 'log')) ?? String(value);
        } catch {
          return String(value);
        }
      })
      .join(' ');
    const size = byteLength(line) + 1;
    if (logged + size > limits.logBytes) {
      if (!logTruncated) {
        logTruncated = true;
        logs.push(`(log truncated at ${limits.logBytes} bytes)`);
      }
      return;
    }
    logged += size;
    logs.push(line);
    port.log?.(line);
  }

  const builders = stepBuilders({
    node,
    condition,
    steps,
    invalid: (message) => fail('SDK-0012', message),
    step: wrapNode,
  });

  const pack = (id: unknown): unknown => {
    open();
    if (typeof id !== 'string' || id === '') fail('SDK-0012', 'm.pack takes the id of a pack');
    return packWriter(id as string);
  };

  const m: Dict = Object.freeze({
    pack,
    ref: Object.freeze(refFactory()),
    actions: Object.freeze(actionBuilders()),
    conditions: Object.freeze(entryBuilders(surface.behavior.conditions, 'm.conditions', true)),
    mechanics: Object.freeze(entryBuilders(surface.behavior.mechanics, 'm.mechanics', true)),
    triggers: Object.freeze(triggerBuilders()),
    fn: Object.freeze(functionBuilders()),
    recipes: Object.freeze(recipeBuilders()),
    guiComponents: Object.freeze(layerBuilders()),
    itemSources: Object.freeze(entryBuilders(surface.behavior.itemSources, 'm.itemSources', false)),
    listSources: Object.freeze(entryBuilders(surface.behavior.listSources, 'm.listSources', false)),
    steps: Object.freeze(builders),
    mm,
    expr,
    summary,
    fail: (message: unknown): never => fail('SDK-0002', typeof message === 'string' ? message : thrownMessage(message)),
    hasCapability: (name: unknown): boolean => typeof name === 'string' && capabilityOn(capabilities, name),
    edit,
    read: readApi(),
    docs: docsApi(),
    sdk: Object.freeze({ version: SDK_VERSION }),
    log,
  });

  // ---- the end of the run -------------------------------------------------------------------------

  function checkAssets(): void {
    if (assetRefs.length === 0 || sessionPack === undefined) return;
    const data = read('m.ref.asset', { pack: sessionPack });
    const listed = (Array.isArray(data.assets) ? data.assets : [])
      .map((row) => (isPlain(row) && typeof (row as Dict).path === 'string' ? ((row as Dict).path as string) : ''))
      .filter((path) => path !== '');
    const have = new Set(listed);
    const seen = new Set<string>();
    for (const asset of assetRefs) {
      if (seen.has(asset.path)) continue;
      seen.add(asset.path);
      if (have.has(asset.path) || have.has(`assets/${asset.path}`)) continue;
      const close = nearest(asset.path, listed);
      diagnostics.push(diagnostic('SDK-0011', `no asset "${asset.path}" in pack "${sessionPack}"`, {
        hint: close.length > 0 ? `nearest assets: ${close.join(', ')}; the owner uploads assets, a program cannot` : 'the pack has no assets; ask the owner to upload it',
        source: asset.source,
      }));
    }
  }

  function finish(outcome: Outcome = {}): ProgramResult {
    if (result !== undefined) return result;
    let returned: JsonValue | null = null;
    try {
      if ('threw' in outcome && outcome.threw !== undefined && !isSdkError(outcome.threw)) {
        const where = typeof outcome.threw === 'object' && outcome.threw !== null ? positionIn((outcome.threw as { stack?: string }).stack, programFile) : undefined;
        diagnostics.push(diagnostic('SDK-0001', thrownMessage(outcome.threw), { source: where }));
      }
      try {
        checkAssets();
      } catch (error) {
        if (!isSdkError(error)) throw error;
      }
      if (outcome.returned !== undefined && !diagnostics.some((entry) => entry.severity === 'error')) {
        try {
          returned = node(outcome.returned, 'the return value');
          if (byteLength(JSON.stringify(returned)) > limits.returnBytes) {
            returned = null;
            diagnostics.push(diagnostic('SDK-0014', `the return value is larger than ${limits.returnBytes} bytes`, { hint: 'summarize in the program: return counts and ids, not documents' }));
          }
        } catch (error) {
          if (!isSdkError(error)) throw error;
          returned = null;
          const failed = diagnostics.pop();
          diagnostics.push(diagnostic('SDK-0014', failed?.message ?? 'the return value cannot be returned'));
        }
      }
    } finally {
      closed = true;
    }
    const ok = !diagnostics.some((entry) => entry.severity === 'error');
    result = JSON.parse(JSON.stringify({
      ok,
      sdkVersion: SDK_VERSION,
      pack: operations.length > 0 ? sessionPack ?? null : null,
      operations: ok ? operations : [],
      sources: ok ? sources : [],
      diagnostics,
      summary: summaryText,
      logs,
      returned: ok ? returned : null,
      stats: { reads, readBytes, staged: operations.length, documents: documents.size },
    })) as ProgramResult;
    port.done?.(result);
    return result;
  }

  return { m, finish, late: () => [...late] };
}

/** The first frame of `stack` that lies in `file`: `(file:line:column)` or `at file:line:column`. */
export function positionIn(stack: string | undefined, file: string | undefined): SourcePosition | undefined {
  if (stack === undefined || file === undefined) return undefined;
  for (const line of stack.split('\n')) {
    const found = /([^\s()]+):(\d+):(\d+)\)?\s*$/.exec(line);
    if (found !== null && found[1] === file) return { line: Number(found[2]), column: Number(found[3]) };
  }
  return undefined;
}
