/**
 * The types and the runtime cannot drift (codemode.md section 3.1, issue 339): every member the declarations
 * promise exists on the object the core builds, and every member the core builds is in the declarations. The
 * test walks the declarations with the TypeScript checker (the fixed `core.d.ts` plus the text the generator
 * emits for a surface) and the object the BUNDLED core builds from the same surface, level by level: `m`, each
 * catalog, the pack writer, each collection, each handle, each rule set. It runs over both recorded
 * registries, a jar without a capability, and a jar that predates the capability list, so the "smaller
 * surface" rule of codemode.md section 7 is held in types and at run time alike.
 */
import { describe, expect, it } from 'vitest';
import ts from 'typescript';
import vm from 'node:vm';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import type { SurfaceDocument } from '../src/lib/codemode/generate/surface';
import { readSnapshot, surfaceNamed, surfaceOf, withFakeAddon } from './helpers/codemode';
import { bareContext, fakePort } from './helpers/sdk-runner';
import { declarationProgram } from './helpers/typecheck';

const current = surfaceNamed('plugin-current');
const surfaces: [string, SurfaceDocument][] = [
  ['plugin-current', current],
  ['with-addon', surfaceOf(withFakeAddon(readSnapshot('plugin-current')))],
  ['a jar without logic', { ...current, capabilities: (current.capabilities ?? []).filter((name) => name !== 'logic') }],
  ['a jar without codemode', { ...current, capabilities: (current.capabilities ?? []).filter((name) => name !== 'codemode') }],
  ['a jar that predates the capability list', { ...current, capabilities: null }],
];

/** What a walk of the runtime object reports. */
interface Shape {
  m: string[];
  nested: Record<string, string[]>;
  pack: string[];
  collections: Record<string, string[]>;
  handles: Record<string, string[]>;
  rules: Record<string, string[]>;
  /** The content type name behind each collection member. */
  kinds: Record<string, string>;
}

/** Walks the object the bundled core builds for `surface`. */
async function runtimeShape(surface: SurfaceDocument): Promise<Shape> {
  const context = await bareContext();
  const port = fakePort({ packs: ['bench'] });
  const holder = context as Record<string, unknown>;
  holder.__port = { read: (op: string, payload: Record<string, unknown>) => port.read(op, JSON.parse(JSON.stringify(payload))) };
  holder.__surface = JSON.stringify(surface);
  const text = vm.runInContext(
    `(function () {
      var session = MaterioneCore.createSession({ surface: JSON.parse(__surface), port: __port });
      var m = session.m;
      var keys = function (o) { return Object.keys(o).sort(); };
      var out = { m: keys(m), nested: {}, pack: [], collections: {}, handles: {}, rules: {}, kinds: {} };
      keys(m).forEach(function (k) {
        var v = m[k];
        if (typeof v === 'object' && v !== null) out.nested[k] = keys(v);
      });
      var writer = m.pack('bench');
      out.pack = keys(writer);
      var n = 0;
      keys(writer).forEach(function (k) {
        if (k === 'id') return;
        out.collections[k] = keys(writer[k]);
        n += 1;
        var handle = writer[k].create('probe_' + n, {});
        out.handles[k] = keys(handle);
        out.kinds[k] = handle.kind;
        if (handle.rules) out.rules[k] = keys(handle.rules);
      });
      return JSON.stringify(out);
    })()`,
    context,
  ) as string;
  return JSON.parse(text) as Shape;
}

/** The names of the properties of a type, without the brands the types carry and the runtime does not. */
function names(checker: ts.TypeChecker, type: ts.Type): string[] {
  return checker.getPropertiesOfType(type).map((symbol) => symbol.getName()).filter((name) => !name.startsWith('__')).sort();
}

function globalType(checker: ts.TypeChecker, program: ts.Program, name: string): ts.Type | undefined {
  const file = program.getSourceFiles().find((source) => source.fileName === '/sdk/core.d.ts');
  if (file === undefined) return undefined;
  const symbol = checker.resolveName(name, undefined, ts.SymbolFlags.Type, false);
  return symbol === undefined ? undefined : checker.getDeclaredTypeOfSymbol(symbol);
}

/** The return type of a callable member's first signature. */
function returnOf(checker: ts.TypeChecker, member: ts.Symbol): ts.Type | undefined {
  const signatures = checker.getTypeOfSymbol(member).getCallSignatures();
  return signatures[0] === undefined ? undefined : checker.getReturnTypeOfSignature(signatures[0]);
}

describe.each(surfaces)('types and runtime agree: %s', (_name, surface) => {
  const sdk = generateSdk(surface);
  const program = declarationProgram(sdk);
  const checker = program.getTypeChecker();
  const materione = globalType(checker, program, 'Materione');

  it('the declarations compile and the core type exists', () => {
    expect(materione).toBeDefined();
  });

  it('m: the same members, nothing more and nothing less', async () => {
    const shape = await runtimeShape(surface);
    expect(names(checker, materione as ts.Type)).toEqual(shape.m);
  });

  it('each catalog and namespace of m has exactly the members its declaration lists', async () => {
    const shape = await runtimeShape(surface);
    for (const symbol of checker.getPropertiesOfType(materione as ts.Type)) {
      const type = checker.getTypeOfSymbol(symbol);
      if (type.getCallSignatures().length > 0) continue;
      const key = symbol.getName();
      expect(shape.nested[key], `m.${key} exists at run time as an object`).toBeDefined();
      expect(names(checker, type), `m.${key}`).toEqual(shape.nested[key]);
    }
    // and every object the runtime builds is one the declarations list
    const declared = new Set(names(checker, materione as ts.Type));
    for (const key of Object.keys(shape.nested)) expect(declared.has(key), `m.${key} is declared`).toBe(true);
  });

  it('the pack writer, each collection, each handle and each rule set', async () => {
    const shape = await runtimeShape(surface);
    const pack = props(checker, materione as ts.Type, 'pack');
    const writer = returnOf(checker, pack) as ts.Type;
    expect(names(checker, writer)).toEqual(shape.pack);
    const writerProps = new Map(checker.getPropertiesOfType(writer).map((symbol) => [symbol.getName(), symbol]));
    for (const [member, runtime] of Object.entries(shape.collections)) {
      const collection = checker.getTypeOfSymbol(writerProps.get(member) as ts.Symbol);
      expect(names(checker, collection), `${member} collection`).toEqual(runtime);
      const create = returnOf(checker, checker.getPropertiesOfType(collection).find((symbol) => symbol.getName() === 'create') as ts.Symbol) as ts.Type;
      expect(names(checker, create), `${member} handle`).toEqual(shape.handles[member]);
      const handleRules = checker.getPropertiesOfType(create).find((symbol) => symbol.getName() === 'rules');
      if (handleRules === undefined) {
        expect(shape.rules[member], `${member} has no rules in the types, so none at run time`).toBeUndefined();
      } else {
        expect(names(checker, checker.getTypeOfSymbol(handleRules)), `${member} rules`).toEqual(shape.rules[member]);
      }
    }
    // a declared collection that the runtime lacks fails the key comparison above; one the runtime adds fails here
    expect(Object.keys(shape.collections).sort()).toEqual([...writerProps.keys()].filter((name) => name !== 'id').sort());
  });

  it('the maps m.read.get and m.edit are typed from list every content type, and the handles those a writer has', async () => {
    const shape = await runtimeShape(surface);
    const fields = globalType(checker, program, 'ContentTypeFields') as ts.Type;
    expect(names(checker, fields)).toEqual(surface.contentTypes.map((type) => type.name).sort());
    const handles = globalType(checker, program, 'ContentTypeHandles');
    expect(handles === undefined ? [] : names(checker, handles)).toEqual(Object.values(shape.kinds).sort());
  });
});

function props(checker: ts.TypeChecker, type: ts.Type, name: string): ts.Symbol {
  const found = checker.getPropertiesOfType(type).find((symbol) => symbol.getName() === name);
  if (found === undefined) throw new Error(`no ${name}`);
  return found;
}

describe('the drift test sees drift', () => {
  it('fails when the runtime has a member the declarations lack', async () => {
    const sdk = generateSdk(current).replace(/\n {2}takeMoney\([^\n]*\n/, '\n');
    const program = declarationProgram(sdk);
    const checker = program.getTypeChecker();
    const catalog = globalType(checker, program, 'ActionCatalog') as ts.Type;
    const shape = await runtimeShape(current);
    expect(names(checker, catalog)).not.toEqual(shape.nested.actions);
  });
});
