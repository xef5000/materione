// SPIKE (#340) - throwaway. TypeScript in an ordinary Worker of the editor's own origin: it only PARSES
// and CHECKS untrusted text, it never executes it (codemode.md 4.3, 5.1).
import ts from 'typescript';
// @ts-expect-error virtual module produced by build.mjs (lib.*.d.ts texts, no DOM)
import libs from 'virtual:tslibs';

const LIBS = libs as Record<string, string>;
// The wrapper keeps the program body on line 1's tail so TS line N == body line N.
const PREFIX = '(async (m: Sdk) => {';
const SUFFIX = '\n})';

export interface Diag { line: number; col: number; code: number; message: string }

function wrap(body: string) { return PREFIX + body + SUFFIX; }

function transpile(body: string) {
  const out = ts.transpileModule(wrap(body), {
    reportDiagnostics: true,
    fileName: 'program.ts',
    compilerOptions: { target: ts.ScriptTarget.ES2022, sourceMap: true, module: ts.ModuleKind.None, removeComments: false },
  });
  const syntax: Diag[] = (out.diagnostics ?? []).map((d) => {
    const p = d.file && d.start !== undefined ? d.file.getLineAndCharacterOfPosition(d.start) : { line: 0, character: 0 };
    return { line: p.line + 1, col: p.character + 1, code: d.code, message: ts.flattenDiagnosticMessageText(d.messageText, '\n') };
  });
  // The expression statement must not gain a trailing `;` problem: evalCode returns the completion value.
  return { js: out.outputText.replace(/\/\/# sourceMappingURL=.*$/m, ''), map: JSON.parse(out.sourceMapText!), syntax };
}

let cachedProgram: ts.Program | undefined;
// Lib and SDK declaration SourceFiles survive between checks (the SDK .d.ts changes rarely): only the
// program file is re-parsed, which is what makes the warm check cheap.
const sf = new Map<string, { text: string; file: ts.SourceFile }>();
function check(body: string, dts: string): Diag[] {
  const files: Record<string, string> = { '/program.ts': wrap(body), '/sdk.d.ts': dts };
  const get = (name: string, text: string) => {
    const hit = sf.get(name);
    if (hit && hit.text === text) return hit.file;
    const file = ts.createSourceFile(name, text, ts.ScriptTarget.ES2022, true);
    sf.set(name, { text, file });
    return file;
  };
  const host: ts.CompilerHost = {
    getSourceFile: (name) => {
      const base = name.replace(/^\//, '');
      if (name in files) return get(name, files[name]!);
      if (base in LIBS) return get(name, LIBS[base]!);
      return undefined;
    },
    getDefaultLibFileName: () => 'lib.es2022.d.ts',
    getDefaultLibLocation: () => '/',
    writeFile: () => {},
    getCurrentDirectory: () => '/',
    getCanonicalFileName: (f) => f,
    useCaseSensitiveFileNames: () => true,
    getNewLine: () => '\n',
    fileExists: (f) => f in files || f.replace(/^\//, '') in LIBS,
    readFile: (f) => files[f] ?? LIBS[f.replace(/^\//, '')],
    directoryExists: () => true,
  };
  const program = ts.createProgram({
    rootNames: ['/program.ts', '/sdk.d.ts'],
    options: { target: ts.ScriptTarget.ES2022, strict: true, noEmit: true, types: [], skipLibCheck: true, lib: ['lib.es2022.d.ts'] },
    host,
    oldProgram: cachedProgram,
  });
  cachedProgram = program;
  return ts.getPreEmitDiagnostics(program).map((d) => {
    const p = d.file && d.start !== undefined ? d.file.getLineAndCharacterOfPosition(d.start) : { line: 0, character: 0 };
    return { line: p.line + 1, col: p.character + 1, code: d.code, message: ts.flattenDiagnosticMessageText(d.messageText, '\n') };
  });
}

self.onmessage = (e: MessageEvent) => {
  const d = e.data as { id: number; t: 'transpile' | 'check' | 'ping'; body?: string; dts?: string };
  const t0 = performance.now();
  try {
    if (d.t === 'ping') (self as unknown as Worker).postMessage({ id: d.id, ok: true, ms: 0 });
    else if (d.t === 'transpile') (self as unknown as Worker).postMessage({ id: d.id, ok: true, ...transpile(d.body!), ms: performance.now() - t0 });
    else (self as unknown as Worker).postMessage({ id: d.id, ok: true, diags: check(d.body!, d.dts!), ms: performance.now() - t0 });
  } catch (err) {
    (self as unknown as Worker).postMessage({ id: d.id, ok: false, error: String((err as Error).stack ?? err) });
  }
};
