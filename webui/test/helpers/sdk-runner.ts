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
import type { Limits, ProgramResult, ReadReply, ResolveRequest, SdkDiagnostic } from '../../src/lib/codemode/core';

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

export { fakePort, type Call, type World, type WorldResource } from './world';
import { fakePort, type Call, type World } from './world';

// ---- running a program --------------------------------------------------------------------------------

export interface RunOptions {
  surface: SurfaceDocument;
  /** The program, TypeScript. */
  program: string;
  world?: World;
  limits?: Partial<Limits>;
  /** How long the host takes to answer each read: a slow host, to prove the end of a run does not depend on the order of answers. */
  readDelayMs?: number;
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
    read: (op: string, payload: Record<string, unknown>) =>
      new Promise<ReadReply>((resolveReply) => {
        const answer = port.read(op, JSON.parse(JSON.stringify(payload)));
        if ((options.readDelayMs ?? 0) > 0) setTimeout(() => resolveReply(answer), options.readDelayMs);
        else resolveReply(answer);
      }),
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
