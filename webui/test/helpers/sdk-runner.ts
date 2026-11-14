// Runs a program against the SDK core the way the sandbox will (codemode.md section 4, 5.2): the core is
// BUNDLED (one plain-ES2020 script, no imports) and executed in a bare `vm` context that has the language's
// intrinsics and nothing else (no console, timers, fetch, process or require), with a fake port that answers
// the core's reads from a small recorded world. The program is TypeScript, transpiled to ES2020 the way the
// editor will before it runs. No browser, no QuickJS: the contract under test is the bundle's.
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';
import type { SurfaceDocument } from '../../src/lib/codemode/generate/surface';
import type { Limits, ProgramResult, ReadReply, ResolveReply, ResolveRequest, SdkDiagnostic } from '../../src/lib/codemode/core';
import { nearest } from '../../src/lib/codemode/core/values';

const CORE_ENTRY = fileURLToPath(new URL('../../src/lib/codemode/core/index.ts', import.meta.url));

let bundled: Promise<string> | undefined;

/** The core as one plain script defining `MaterioneCore`. */
export function bundleCore(): Promise<string> {
  bundled ??= build({
    entryPoints: [CORE_ENTRY],
    bundle: true,
    format: 'iife',
    globalName: 'MaterioneCore',
    target: 'es2020',
    platform: 'neutral',
    write: false,
    logLevel: 'error',
  }).then((result) => result.outputFiles[0]?.text ?? '');
  return bundled;
}

// ---- the world a fake port answers from ---------------------------------------------------------------

export interface WorldResource {
  pack?: string;
  type: string;
  path: string;
  id: string;
  name?: string;
  health?: string;
}

export interface World {
  packs: string[];
  builtin?: string[];
  /** Catalog rows by pack. */
  resources?: Record<string, WorldResource[]>;
  /** Documents by `pack/path`: their revision and parsed tree. */
  documents?: Record<string, { rev: number; parsed: unknown; computed?: unknown }>;
  /** Asset paths by pack. */
  assets?: Record<string, string[]>;
  /** Recipe expansions by recipe id. */
  recipes?: Record<string, { rules: unknown[]; warnings?: unknown[] } | { error: string }>;
  references?: Record<string, unknown[]>;
  history?: Record<string, unknown[]>;
  diagnostics?: unknown[];
}

export interface Call {
  op: string;
  payload: unknown;
}

const ok = (data: unknown): ReadReply => ({ ok: true, data });
const refuse = (message: string, code = 'MTR-0008'): ReadReply => ({ ok: false, error: { code, message } });

/**
 * Answers `m.read.*` ops and the seal's one batched resolve from a world, and records every call (a resolve is
 * recorded as the op `resolve`). The reply to a read is synchronous here; the run wraps it in a promise, as a host would.
 */
export function fakePort(world: World, calls: Call[] = []): {
  read(op: string, payload: Record<string, unknown>): ReadReply;
  resolve(request: ResolveRequest): ResolveReply;
  calls: Call[];
} {
  return {
    calls,
    resolve(request) {
      calls.push({ op: 'resolve', payload: request });
      return answerResolve(world, request);
    },
    read(op, payload) {
      calls.push({ op, payload });
      switch (op) {
        case 'browse.packs':
          return ok({ packs: world.packs, ...(world.builtin && world.builtin.length > 0 ? { builtin: world.builtin } : {}) });
        case 'authoring.summaries': {
          const pack = String(payload.pack);
          const rows = (world.resources?.[pack] ?? [])
            .filter((row) => payload.type === undefined || row.type.toLowerCase() === String(payload.type).toLowerCase())
            .filter((row) => payload.query === undefined || row.id.toLowerCase().includes(String(payload.query).toLowerCase()));
          const offset = typeof payload.offset === 'number' ? payload.offset : 0;
          const limit = typeof payload.limit === 'number' ? payload.limit : 100;
          const page = rows.slice(offset, offset + limit).map((row) => ({ pack, name: row.id, health: 'ok', ...row }));
          const next = offset + limit < rows.length ? { nextOffset: offset + limit } : {};
          return ok({ resources: page, total: rows.length, ...next });
        }
        case 'browse.get': {
          const found = world.documents?.[`${String(payload.pack)}/${String(payload.path)}`];
          if (found === undefined) return refuse(`no document ${String(payload.path)}`);
          return ok({ raw: '', sha256: 'x', ...found });
        }
        case 'authoring.references':
          return ok({ hits: world.references?.[`${String(payload.pack)}:${String(payload.target)}`] ?? [] });
        case 'browse.history':
          return ok({ revisions: world.history?.[`${String(payload.pack)}/${String(payload.path)}`] ?? [] });
        case 'admin.diagnostics.recent':
          return ok({ diagnostics: world.diagnostics ?? [] });
        case 'assets.list':
          return ok({ assets: (world.assets?.[String(payload.pack)] ?? []).map((path) => ({ path, bytes: 1, kind: 'texture' })) });
        case 'logic.instantiate': {
          const expansion = world.recipes?.[String(payload.recipe)];
          if (expansion === undefined) return refuse(`unknown recipe '${String(payload.recipe)}': no recipe with that id is registered`);
          if ('error' in expansion) return refuse(expansion.error);
          return ok({ rules: expansion.rules, warnings: expansion.warnings ?? [] });
        }
        default:
          return refuse(`the fake port does not answer ${op}`, 'MTR-9999');
      }
    },
  };
}

/** The seal's batch answered from a world: what a host does with the read-only editor ops. */
function answerResolve(world: World, request: ResolveRequest): ResolveReply {
  const builtin = world.builtin ?? [];
  return {
    packs: request.packs.map((pack) => {
      if (builtin.includes(pack)) return { state: 'builtin' as const };
      if (world.packs.includes(pack)) return { state: 'ok' as const };
      return { state: 'missing' as const, nearest: nearest(pack, world.packs) };
    }),
    refs: request.refs.map((ref) => {
      const rows = (world.resources?.[ref.pack] ?? []).filter((row) => row.type.toLowerCase() === ref.directory.toLowerCase());
      const row = rows.find((candidate) => candidate.id === ref.id);
      if (row === undefined) return { found: false as const, nearest: nearest(`${ref.pack}:${ref.id}`, rows.map((candidate) => `${ref.pack}:${candidate.id}`)) };
      if (!ref.needsDocument) return { found: true as const };
      const document = world.documents?.[`${ref.pack}/${row.path}`];
      if (document === undefined) return { found: true as const, path: row.path };
      const tree = (document.parsed as Record<string, Record<string, unknown>> | undefined)?.[ref.id];
      const held = ref.rulesKey === undefined ? undefined : tree?.[ref.rulesKey];
      return { found: true as const, path: row.path, rev: document.rev, rules: Array.isArray(held) ? held.length : 0 };
    }),
    instantiate: request.instantiate.map((entry) => {
      const expansion = world.recipes?.[entry.recipe];
      if (expansion === undefined) return { error: { code: 'MTR-0008', message: `unknown recipe '${entry.recipe}': no recipe with that id is registered` } };
      if ('error' in expansion) return { error: { code: 'MTR-0008', message: expansion.error } };
      return { rules: expansion.rules as never, warnings: expansion.warnings ?? [] };
    }),
    assets: request.assets.map((asset) => {
      const listed = world.assets?.[asset.pack] ?? [];
      if (listed.includes(asset.path) || listed.includes(`assets/${asset.path}`)) return { found: true };
      return { found: false, nearest: nearest(asset.path, listed) };
    }),
  };
}

// ---- running a program --------------------------------------------------------------------------------

export interface RunOptions {
  surface: SurfaceDocument;
  /** The program, TypeScript. */
  program: string;
  world?: World;
  limits?: Partial<Limits>;
}

export interface Run {
  result: ProgramResult;
  calls: Call[];
  late: SdkDiagnostic[];
  /** The ES2020 the program became. */
  js: string;
}

/** The ES2020 a program becomes, and the map from it back to the program's own lines (what the host does before it runs one). */
export function transpileWithMap(program: string): { js: string; map: SourceMap } {
  const output = ts.transpileModule(program, {
    fileName: 'program.ts',
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext, removeComments: false, sourceMap: true },
  });
  return { js: output.outputText.replace(/\/\/# sourceMappingURL=.*$/m, ''), map: JSON.parse(output.sourceMapText ?? '{}') as SourceMap };
}

export function transpile(program: string): string {
  return transpileWithMap(program).js;
}

export interface SourceMap {
  mappings: string;
}

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Decodes a source map's mappings into per-line segments `[generatedColumn, sourceLine, sourceColumn]` (all 0-based). */
export function decodeMappings(mappings: string): number[][][] {
  const lines: number[][][] = [];
  let sourceLine = 0;
  let sourceColumn = 0;
  for (const line of mappings.split(';')) {
    const segments: number[][] = [];
    let generatedColumn = 0;
    for (const text of line.split(',')) {
      if (text === '') continue;
      const values: number[] = [];
      let shift = 0;
      let value = 0;
      for (const char of text) {
        const digit = BASE64.indexOf(char);
        value += (digit & 31) << shift;
        if (digit & 32) {
          shift += 5;
        } else {
          values.push(value & 1 ? -(value >> 1) : value >> 1);
          value = 0;
          shift = 0;
        }
      }
      generatedColumn += values[0] ?? 0;
      if (values.length >= 4) {
        sourceLine += values[2] ?? 0;
        sourceColumn += values[3] ?? 0;
        segments.push([generatedColumn, sourceLine, sourceColumn]);
      }
    }
    lines.push(segments);
  }
  return lines;
}

/** The program position (1-based) of a generated position (1-based): the last segment at or before it. */
export function originalPosition(decoded: number[][][], line: number, column: number): { line: number; column: number } {
  const segments = decoded[line - 1] ?? [];
  let best: number[] | undefined;
  for (const segment of segments) if ((segment[0] ?? 0) <= column - 1) best = segment;
  best ??= segments[0];
  return best === undefined ? { line, column } : { line: (best[1] ?? 0) + 1, column: (best[2] ?? 0) + 1 };
}

/** A bare context: the language's intrinsics, and none of the host's globals. */
export async function bareContext(): Promise<vm.Context> {
  const context = vm.createContext({});
  vm.runInContext(await bundleCore(), context, { filename: 'core.js' });
  return context;
}

export async function run(options: RunOptions): Promise<Run> {
  const context = await bareContext();
  const port = fakePort(options.world ?? { packs: ['bench'] });
  const holder = context as Record<string, unknown>;
  holder.__port = {
    read: (op: string, payload: Record<string, unknown>) => Promise.resolve(port.read(op, JSON.parse(JSON.stringify(payload)))),
    resolve: (request: ResolveRequest) => Promise.resolve(port.resolve(JSON.parse(JSON.stringify(request)))),
  };
  holder.__surface = JSON.stringify(options.surface);
  holder.__limits = JSON.stringify(options.limits ?? {});
  vm.runInContext(
    `var __session = MaterioneCore.createSession({ surface: JSON.parse(__surface), port: __port, limits: JSON.parse(__limits), programFile: 'program.js' }); var m = __session.m;`,
    context,
    { filename: 'boot.js' },
  );
  const { js, map } = transpileWithMap(options.program);
  // the program shares line 1 with the wrapper, so its generated lines are its own (its first line's columns shift by the prefix)
  const prefix = '(async function () { var outcome; try { outcome = { returned: await (async function () {';
  const script = `${prefix}${js}\n})() }; } catch (error) { outcome = { threw: error }; } var finished = await __session.finish(outcome); return JSON.stringify({ result: finished, late: __session.late() }); })()`;
  const text = (await vm.runInContext(script, context, { filename: 'program.js' })) as string;
  const parsed = JSON.parse(text) as { result: ProgramResult; late: SdkDiagnostic[] };
  // the host maps positions in the transpiled program back to the program the agent wrote
  const decoded = decodeMappings(map.mappings);
  const mapOne = <T extends { line: number; column: number } | null | undefined>(position: T): T => {
    if (position === null || position === undefined) return position;
    const column = position.line === 1 ? position.column - prefix.length : position.column;
    return originalPosition(decoded, position.line, column) as T;
  };
  parsed.result.sources = parsed.result.sources.map((source) => mapOne(source));
  for (const list of [parsed.result.diagnostics, parsed.late]) {
    for (const entry of list) if (entry.source !== undefined) entry.source = mapOne(entry.source);
  }
  return { result: parsed.result, calls: port.calls, late: parsed.late, js };
}

/** Runs code in a fresh bare context with the bundle loaded and returns what the global expression `source` gives (JSON). */
export async function inBare(source: string): Promise<unknown> {
  const context = await bareContext();
  return JSON.parse(vm.runInContext(`JSON.stringify(${source})`, context) as string);
}

export function readText(path: string): string {
  return readFileSync(path, 'utf8');
}
