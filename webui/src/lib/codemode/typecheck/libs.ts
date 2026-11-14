/**
 * The library the program is checked against (codemode.md section 4.3): ES5 to ES2022 and the decorators
 * declarations the ES libraries reference, read from the pinned `typescript` package at build time and bundled
 * into the Worker. No DOM, no Node, no webworker lib, no `@types`: a program that names `window`, `fetch` or `process`
 * is a type error because nothing declares them.
 *
 * Only the Worker bundle (and the vitest suite) imports this file, so none of it is in the editor's own bundle.
 */
const files = import.meta.glob(
  [
    '/node_modules/typescript/lib/lib.es5.d.ts',
    '/node_modules/typescript/lib/lib.es201[5-9]*.d.ts',
    '/node_modules/typescript/lib/lib.es202[0-2]*.d.ts',
    '/node_modules/typescript/lib/lib.decorators*.d.ts',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>;

/** `lib.*.d.ts` texts by file name. */
export const LIB_FILES: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(files).map(([path, text]) => [path.slice(path.lastIndexOf('/') + 1), text]),
);
