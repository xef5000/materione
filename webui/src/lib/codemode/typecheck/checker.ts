/**
 * The checker (codemode.md section 5.1): the TypeScript compiler over one program and the generated surface.
 *
 * It PARSES, CHECKS and TRANSPILES untrusted text and runs none of it: nothing here evaluates, imports or
 * fetches anything the program names. It is pure and DOM-free so the same code runs in the Worker (`worker.ts`)
 * and in vitest on Node's TypeScript API; only the library texts are injected.
 *
 * What the program is checked against, exactly: the ES2022 library (no DOM, no Node, no `@types`), the fixed
 * core declarations (`generate/core.d.ts`) and the generated surface text. Module resolution is off
 * (`noResolve`), so no `import`, `/// <reference path>` or `types` can reach a file the host did not hand over.
 */
import ts from 'typescript';
import {
  DEFAULT_CHECK_LIMITS,
  type CheckCode,
  type CheckDiagnostic,
  type CheckInput,
  type CheckLimits,
  type CheckResult,
  type MissingPrimitive,
  type RawSourceMap,
} from './types';
import { offsetPosition, programPosition, wrapProgram } from './wrap';

export interface CheckerOptions {
  /** `lib.*.d.ts` texts by file name (`lib.es5.d.ts`): the whole library the program sees. */
  libs: Readonly<Record<string, string>>;
  /** The fixed core declarations. */
  core: string;
  limits?: Partial<CheckLimits>;
}

export interface Checker {
  check(input: CheckInput): CheckResult;
}

const PROGRAM = '/program/main.ts';
const CORE = '/sdk/core.d.ts';
const SDK = '/sdk/sdk.d.ts';
const LIB_ROOT = '/';

/** Names a program might reach for. The message is TypeScript's; the hint is ours, because TypeScript's advice is to add a DOM lib. */
const AMBIENT_NAMES = new Set([
  'window', 'self', 'document', 'navigator', 'location', 'fetch', 'XMLHttpRequest', 'WebSocket', 'Worker', 'localStorage',
  'sessionStorage', 'indexedDB', 'process', 'require', 'module', 'exports', 'Buffer', 'setTimeout', 'setInterval',
  'clearTimeout', 'clearInterval', 'queueMicrotask', 'setImmediate', 'console', 'alert', 'postMessage', 'importScripts',
]);

const COMPILER_OPTIONS: ts.CompilerOptions = {
  target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  lib: ['lib.es2022.d.ts'],
  types: [],
  strict: true,
  noEmit: true,
  noResolve: true,
  skipLibCheck: true,
};

export function createChecker(options: CheckerOptions): Checker {
  const limits: CheckLimits = { ...DEFAULT_CHECK_LIMITS, ...options.limits };
  // Parsed declaration files survive between checks: only the program is re-parsed when the surface is unchanged,
  // which is what makes a warm check cheap (the spike: 4.5 ms against 83 ms cold). A changed surface text (a registry
  // change, codemode.md section 7) is a changed file here, so the program is rebuilt over the new declarations.
  const parsed = new Map<string, { text: string; file: ts.SourceFile }>();
  let previous: ts.Program | undefined;

  const parse = (name: string, text: string): ts.SourceFile => {
    const hit = parsed.get(name);
    if (hit !== undefined && hit.text === text) return hit.file;
    const file = ts.createSourceFile(name, text, ts.ScriptTarget.ES2022, true);
    parsed.set(name, { text, file });
    return file;
  };

  function build(program: string, sdk: string): ts.Program {
    const files = new Map<string, string>([[CORE, options.core], [SDK, sdk], [PROGRAM, program]]);
    const libName = (name: string): string | undefined => {
      const base = name.startsWith(LIB_ROOT) ? name.slice(LIB_ROOT.length) : name;
      return Object.hasOwn(options.libs, base) ? base : undefined;
    };
    const host: ts.CompilerHost = {
      getSourceFile: (name) => {
        const text = files.get(name);
        if (text !== undefined) return parse(name, text);
        const lib = libName(name);
        return lib === undefined ? undefined : parse(`/${lib}`, options.libs[lib]!);
      },
      getDefaultLibFileName: () => `${LIB_ROOT}lib.es2022.d.ts`,
      getDefaultLibLocation: () => LIB_ROOT,
      writeFile: () => undefined,
      getCurrentDirectory: () => '/',
      getCanonicalFileName: (name) => name,
      useCaseSensitiveFileNames: () => true,
      getNewLine: () => '\n',
      fileExists: (name) => files.has(name) || libName(name) !== undefined,
      readFile: (name) => {
        const lib = libName(name);
        return files.get(name) ?? (lib === undefined ? undefined : options.libs[lib]);
      },
      directoryExists: () => true,
      getDirectories: () => [],
    };
    const built = ts.createProgram({ rootNames: [CORE, SDK, PROGRAM], options: COMPILER_OPTIONS, host, oldProgram: previous });
    previous = built;
    return built;
  }

  return {
    check(input: CheckInput): CheckResult {
      const tooBig = refuseOversize(input, limits);
      if (tooBig !== undefined) return { ok: false, diagnostics: [tooBig], truncated: false };

      // The program is checked as the body of an async function (wrap.ts): reads are awaited, and every line and column below is
      // mapped back to the program the agent wrote.
      const built = build(wrapProgram(input.program), input.sdk);
      const file = built.getSourceFile(PROGRAM)!;
      const checker = built.getTypeChecker();
      const catalogs = catalogNames(built.getSourceFile(SDK)!);

      const found: CheckDiagnostic[] = [...refusals(file, input.program)];
      for (const diagnostic of ts.getPreEmitDiagnostics(built, file)) {
        found.push(convert(diagnostic, file, input.program, checker, catalogs));
      }
      // The compiler's own order is deterministic; sorting by position keeps the list stable across compiler versions too.
      found.sort((a, b) => a.line - b.line || a.column - b.column || compare(a.code, b.code) || compare(a.message, b.message));
      const diagnostics = found.slice(0, limits.maxDiagnostics);
      const ok = !found.some((entry) => entry.severity === 'error');
      const result: CheckResult = { ok, diagnostics, truncated: found.length > diagnostics.length };
      if (ok) {
        const out = transpile(input.program);
        result.js = out.js;
        result.map = out.map;
      }
      return result;
    },
  };
}

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function refuseOversize(input: CheckInput, limits: CheckLimits): CheckDiagnostic | undefined {
  if (input.program.length > limits.maxProgramChars) {
    return own('CHK-0002', `The program is ${input.program.length} characters; the checker takes at most ${limits.maxProgramChars}.`, 'Split the work into more than one program: each is one transaction in one pack.');
  }
  if (input.sdk.length > limits.maxSdkChars) {
    return own('CHK-0002', `The generated declarations are ${input.sdk.length} characters; the checker takes at most ${limits.maxSdkChars}.`, undefined);
  }
  return undefined;
}

/** A checker diagnostic (not TypeScript's). */
export function own(code: CheckCode, message: string, hint: string | undefined, line = 0, column = 0): CheckDiagnostic {
  const entry: CheckDiagnostic = { code, severity: 'error', message, line, column };
  if (hint !== undefined) entry.hint = hint;
  return entry;
}

/** ES2020 JavaScript and its source map, from the TypeScript emitter alone (the program is not run, not even evaluated). */
function transpile(program: string): { js: string; map: RawSourceMap } {
  const out = ts.transpileModule(program, {
    fileName: 'program.ts',
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext, removeComments: false, sourceMap: true },
  });
  return { js: out.outputText.replace(/\/\/# sourceMappingURL=.*$/m, ''), map: JSON.parse(out.sourceMapText ?? '{}') as RawSourceMap };
}

// --- what the checker refuses, whatever the types say ------------------------------------------------------------

const DIRECTIVE = /@ts-(?:nocheck|ignore|expect-error)\b/g;

/**
 * Constructs that would let a program escape the surface it is checked against: comment directives that switch the
 * checker off, ambient declarations that conjure a global (`declare const fetch: any`), imports and exports (a program is a
 * script body, not a module), and a program that closes its own body to write at the wrapper's top level. The comment scan
 * is textual and so conservative: the same words inside a string are refused too, and the agent rephrases.
 */
function refusals(file: ts.SourceFile, program: string): CheckDiagnostic[] {
  const found: CheckDiagnostic[] = [];
  for (const match of program.matchAll(DIRECTIVE)) {
    const where = offsetPosition(program, match.index);
    found.push(own('CHK-0001', `The comment directive ${match[0]} is not allowed: a program is checked in full.`, 'Fix the type error instead of silencing it.', where.line, where.column));
  }
  const refuse = (node: ts.Node, what: string, hint: string): void => {
    const at = file.getLineAndCharacterOfPosition(node.getStart(file));
    const where = programPosition(program, { line: at.line + 1, column: at.character + 1 });
    found.push(own('CHK-0001', `${what} is not allowed in a program.`, hint, where.line, where.column));
  };
  // the wrapper is the file's one statement; a program that closes it (`})(); ... (() => {`) has more
  if (file.statements.length !== 1 || !ts.isExpressionStatement(file.statements[0]!)) {
    found.push(own('CHK-0001', 'The program closes its own body: statements outside it are not allowed.', 'Balance the braces; a program is one script body.', 1, 1));
  }
  const visit = (node: ts.Node): void => {
    if (ts.isImportDeclaration(node) || ts.isImportEqualsDeclaration(node) || ts.isExportDeclaration(node) || ts.isExportAssignment(node)) {
      refuse(node, 'An import or export', 'A program takes no modules: `m` is the only thing it can use.');
      return;
    }
    if (ts.isModuleDeclaration(node)) {
      refuse(node, 'A module or namespace declaration', 'The program has one global, `m`; declare nothing ambient.');
      return;
    }
    const modifiers = ts.canHaveModifiers(node) ? ts.getModifiers(node) : undefined;
    if (modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.DeclareKeyword || modifier.kind === ts.SyntaxKind.ExportKeyword) === true) {
      refuse(node, 'An ambient `declare` or an export', 'The program has one global, `m`; it cannot declare more.');
    }
    ts.forEachChild(node, visit);
  };
  ts.forEachChild(file, visit);
  return found;
}

// --- converting the compiler's diagnostics -------------------------------------------------------------------------

const SUGGESTION = /Did you mean '([^']+)'\?/;
const MEMBER_MISSING = /^Property '([^']+)' does not exist on type '(.+?)'\./s;
const LITERAL_UNKNOWN = /^Object literal may only specify known properties, and '([^']+)' does not exist in type '(.+?)'\./s;

function convert(diagnostic: ts.Diagnostic, file: ts.SourceFile, program: string, checker: ts.TypeChecker, catalogs: ReadonlySet<string>): CheckDiagnostic {
  const message = ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n');
  const at = diagnostic.file === file && diagnostic.start !== undefined ? file.getLineAndCharacterOfPosition(diagnostic.start) : undefined;
  const where = at === undefined ? undefined : programPosition(program, { line: at.line + 1, column: at.character + 1 });
  const out: CheckDiagnostic = {
    code: `TS${diagnostic.code}`,
    severity: diagnostic.category === ts.DiagnosticCategory.Error ? 'error' : 'warning',
    message,
    line: where?.line ?? 0,
    column: where?.column ?? 0,
  };
  const suggestion = SUGGESTION.exec(message);
  if (suggestion !== null) out.hint = `Did you mean '${suggestion[1]}'?`;

  const name = /^Cannot find name '([^']+)'/.exec(message)?.[1];
  if (name !== undefined && AMBIENT_NAMES.has(name)) {
    out.hint = `A program has no ambient authority: '${name}' does not exist here. The only global is \`m\`.`;
  }

  if (diagnostic.code === 2339 || diagnostic.code === 2353) {
    const gap = classify(diagnostic.code, message, file, diagnostic.start, checker, catalogs);
    if (gap !== undefined) {
      out.missingPrimitive = gap;
      out.hint = gap.nearest.length > 0
        ? `The connected server has no '${gap.member}' on ${gap.catalog}. If one of these is what you meant, use it: ${gap.nearest.join(', ')}. Otherwise report the missing primitive and stop that part of the plan.`
        : `The connected server has no '${gap.member}' on ${gap.catalog}. This is a missing primitive, not a typo: report it and stop that part of the plan, or build what exists.`;
    }
  }
  return out;
}

/** The top-level type names the generated surface declares or extends: the catalogs (`PackWriter`, `ActionCatalog`, `ItemFields`, ...). */
function catalogNames(sdk: ts.SourceFile): Set<string> {
  const names = new Set<string>();
  for (const statement of sdk.statements) {
    if ((ts.isInterfaceDeclaration(statement) || ts.isTypeAliasDeclaration(statement) || ts.isClassDeclaration(statement)) && statement.name !== undefined) {
      names.add(statement.name.text);
    }
  }
  return names;
}

/**
 * A `TS2339` (no such property) or `TS2353` (no such key in an object literal) whose type is one the generated surface
 * declares or extends: the server does not describe this member, which is an answer about the server and not a typo
 * (codemode.md section 6, rule 2). The same error on a type of the fixed core only (`m.nonsense`) is not.
 */
function classify(
  code: number,
  message: string,
  file: ts.SourceFile,
  start: number | undefined,
  checker: ts.TypeChecker,
  catalogs: ReadonlySet<string>,
): MissingPrimitive | undefined {
  const match = (code === 2339 ? MEMBER_MISSING : LITERAL_UNKNOWN).exec(message);
  if (match === null) return undefined;
  // the type is printed as written: `PackWriter`, `ItemFields | undefined`, `Ref<"item">`
  const candidates = match[2]!.split('|').map((part) => part.trim().replace(/<.*$/s, ''));
  const catalog = candidates.find((candidate) => catalogs.has(candidate));
  if (catalog === undefined) return undefined;
  return { member: match[1]!, catalog, nearest: nearest(match[1]!, file, start, checker) };
}

/** Members of the catalog that look like `member`, read through the checker at the diagnostic's own position. */
function nearest(member: string, file: ts.SourceFile, start: number | undefined, checker: ts.TypeChecker): string[] {
  if (start === undefined) return [];
  const node = deepestAt(file, start);
  const parent = node?.parent;
  let type: ts.Type | undefined;
  if (parent !== undefined && ts.isPropertyAccessExpression(parent) && parent.name === node) {
    type = checker.getTypeAtLocation(parent.expression);
  } else if (parent?.parent !== undefined && ts.isObjectLiteralExpression(parent.parent)) {
    type = checker.getContextualType(parent.parent);
  }
  if (type === undefined) return [];
  const lower = member.toLowerCase();
  return checker
    .getPropertiesOfType(checker.getNonNullableType(type))
    .map((symbol) => symbol.getName())
    .map((name) => ({ name, distance: distance(lower, name.toLowerCase()) }))
    .filter((entry) => entry.distance <= Math.max(2, Math.floor(member.length / 3)))
    .sort((a, b) => a.distance - b.distance || compare(a.name, b.name))
    .slice(0, 3)
    .map((entry) => entry.name);
}

function deepestAt(root: ts.Node, position: number): ts.Node | undefined {
  let best: ts.Node | undefined;
  const walk = (node: ts.Node): void => {
    if (position < node.getStart() || position >= node.getEnd()) return;
    best = node;
    ts.forEachChild(node, walk);
  };
  ts.forEachChild(root, walk);
  return best;
}

function distance(a: string, b: string): number {
  const row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let diagonal = row[0]!;
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const up = row[j]!;
      row[j] = Math.min(up + 1, row[j - 1]! + 1, diagonal + (a[i - 1] === b[j - 1] ? 0 : 1));
      diagonal = up;
    }
  }
  return row[b.length]!;
}
