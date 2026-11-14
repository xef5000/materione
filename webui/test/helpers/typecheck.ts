// A TypeScript check of one program against the fixed core and a generated SDK surface, all in memory
// (codemode.md section 5.1): the real `typescript` package, no DOM, no node types, the ES2022 library only.
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { CORE_DTS } from './codemode';

export interface Problem {
  code: number;
  message: string;
  line: number;
}

const PROGRAM = '/program/main.ts';
const CORE = '/sdk/core.d.ts';
const SDK = '/sdk/sdk.d.ts';

const libCache = new Map<string, ts.SourceFile>();

function options(skipLibCheck: boolean): ts.CompilerOptions {
  return {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    lib: ['lib.es2022.d.ts'],
    types: [],
    strict: true,
    noEmit: true,
    skipLibCheck,
  };
}

function build(files: ReadonlyMap<string, string>, skipLibCheck: boolean): ts.Program {
  const compilerOptions = options(skipLibCheck);
  const real = ts.createCompilerHost(compilerOptions, true);
  const host: ts.CompilerHost = {
    ...real,
    fileExists: (name) => files.has(name) || real.fileExists(name),
    readFile: (name) => files.get(name) ?? real.readFile(name),
    getSourceFile: (name, languageVersion, onError, shouldCreate) => {
      const text = files.get(name);
      if (text !== undefined) return ts.createSourceFile(name, text, languageVersion, true);
      const cached = libCache.get(name);
      if (cached !== undefined) return cached;
      const loaded = real.getSourceFile(name, languageVersion, onError, shouldCreate);
      if (loaded !== undefined) libCache.set(name, loaded);
      return loaded;
    },
  };
  return ts.createProgram({ rootNames: [CORE, SDK, PROGRAM].filter((name) => files.has(name)), options: compilerOptions, host });
}

function problems(diagnostics: readonly ts.Diagnostic[], only: string | undefined): Problem[] {
  return diagnostics
    .filter((diagnostic) => only === undefined || diagnostic.file?.fileName === only)
    .map((diagnostic) => {
      const where = diagnostic.file !== undefined && diagnostic.start !== undefined
        ? diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start).line + 1
        : 0;
      return { code: diagnostic.code, message: ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'), line: where };
    });
}

/** The diagnostics of `program` against the core and `sdk`. */
export function checkProgram(program: string, sdk: string): Problem[] {
  const files = new Map<string, string>([[CORE, readFileSync(CORE_DTS, 'utf8')], [SDK, sdk], [PROGRAM, program]]);
  const built = build(files, true);
  return problems(ts.getPreEmitDiagnostics(built), PROGRAM);
}

/** The diagnostics of the core and `sdk` themselves, library files included (nothing skipped). */
export function checkDeclarations(sdk: string): Problem[] {
  const files = new Map<string, string>([[CORE, readFileSync(CORE_DTS, 'utf8')], [SDK, sdk]]);
  const built = build(files, false);
  return problems(ts.getPreEmitDiagnostics(built), undefined);
}

/** The program of the core's declarations and a generated surface, for walking their types (the drift test). */
export function declarationProgram(sdk: string): ts.Program {
  const files = new Map<string, string>([[CORE, readFileSync(CORE_DTS, 'utf8')], [SDK, sdk]]);
  return build(files, true);
}
