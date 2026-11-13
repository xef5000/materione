/**
 * Ban tests for the SDK generator (codemode.md section 3.1, D5, master section 5.6): it reads only
 * self-description. Its source holds no registry id (no content type, action, condition, mechanic, trigger,
 * function, recipe, GUI component or source), imports nothing from the editor's state or the DOM, and
 * touches no browser or network global. The ban is kept by this test, not by review goodwill.
 *
 * The check is lexical: every string literal in the generator's sources is compared with every id the
 * recorded registries carry (the plugin's, and the plugin's plus a fake addon's). A literal that is
 * schema vocabulary and not a registry id is allowed by name, with its reason, below.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { readSnapshot, surfaceOf, withFakeAddon } from './helpers/codemode';

const GENERATE_DIR = fileURLToPath(new URL('../src/lib/codemode/generate', import.meta.url));

/**
 * Schema vocabulary the generator may name: parameter value kinds and widget hints (codemode.md section 3.1)
 * and the reference kinds that name a polymorphic registry. Each coincides with, or could be mistaken for,
 * a registry id; none is one.
 */
const SCHEMA_VOCABULARY: Record<string, string> = {
  number: 'the recipe-parameter value kind; also the id of an expression function',
};

function sourceFiles(): { name: string; text: string }[] {
  return readdirSync(GENERATE_DIR)
    .filter((name) => name.endsWith('.ts') && !name.endsWith('.d.ts'))
    .sort()
    .map((name) => ({ name, text: readFileSync(join(GENERATE_DIR, name), 'utf8') }));
}

/** Every string literal (single, double, or a template without substitutions) of a source file. */
function literalsOf(text: string): string[] {
  const source = ts.createSourceFile('source.ts', text, ts.ScriptTarget.ES2022, true);
  const found: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) found.push(node.text);
    // the literal chunks of a template with substitutions count too
    if (ts.isTemplateHead(node) || ts.isTemplateMiddle(node) || ts.isTemplateTail(node)) found.push(node.text);
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

function importsOf(text: string): string[] {
  const source = ts.createSourceFile('source.ts', text, ts.ScriptTarget.ES2022, true);
  const found: string[] = [];
  source.forEachChild((node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) found.push(node.moduleSpecifier.text);
    if (ts.isExportDeclaration(node) && node.moduleSpecifier !== undefined && ts.isStringLiteral(node.moduleSpecifier)) found.push(node.moduleSpecifier.text);
  });
  return found;
}

function identifiersOf(text: string): Set<string> {
  const source = ts.createSourceFile('source.ts', text, ts.ScriptTarget.ES2022, true);
  const found = new Set<string>();
  const visit = (node: ts.Node): void => {
    if (ts.isIdentifier(node)) found.add(node.text);
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

/** Every id a registry of the recorded snapshots carries. */
function registryIds(): Set<string> {
  const ids = new Set<string>();
  for (const snapshot of [readSnapshot('plugin-current'), withFakeAddon(readSnapshot('plugin-current'))]) {
    const surface = surfaceOf(snapshot);
    const b = surface.behavior;
    for (const type of surface.contentTypes) {
      ids.add(type.name);
      if (type.directory !== undefined) ids.add(type.directory);
    }
    for (const group of [b.actions, b.conditions, b.triggers, b.mechanics, b.functions, b.recipes, b.itemSources, b.listSources, b.inputMethods, b.steps, b.categories, surface.gui.components, surface.gui.containers]) {
      for (const entry of group) ids.add(entry.id);
    }
    for (const capability of surface.capabilities ?? []) ids.add(capability);
  }
  return ids;
}

describe('the SDK generator is registry-agnostic', () => {
  const files = sourceFiles();
  const ids = registryIds();

  it('scans the generator and the recorded registries, not nothing', () => {
    expect(files.map((file) => file.name)).toEqual(expect.arrayContaining(['generate.ts', 'surface.ts']));
    expect(ids.size).toBeGreaterThan(150);
    // a sample of ids the ban is about
    for (const id of ['item', 'run_command', 'materione:backpack', 'click', 'gui.click', 'has_money', 'materione:button', 'example:greet']) {
      expect(ids.has(id), id).toBe(true);
    }
  });

  it('holds no registry id as a string literal', () => {
    const offenders = files.flatMap((file) =>
      literalsOf(file.text)
        .filter((value) => ids.has(value) && !(value in SCHEMA_VOCABULARY))
        .map((value) => `${file.name}: "${value}"`));
    expect(offenders).toEqual([]);
  });

  it('allows only schema vocabulary that coincides with an id, and each allowed word is really used', () => {
    for (const word of Object.keys(SCHEMA_VOCABULARY)) {
      expect(ids.has(word), `${word} is no longer an id: drop it from the allowance`).toBe(true);
      expect(files.some((file) => literalsOf(file.text).includes(word)), `${word} is unused: drop it from the allowance`).toBe(true);
    }
  });

  it('imports nothing from the editor: only the surface types', () => {
    for (const file of files) {
      for (const specifier of importsOf(file.text)) {
        expect(['./surface'], `${file.name} imports ${specifier}`).toContain(specifier);
      }
    }
  });

  it('touches no DOM, browser storage or network global', () => {
    const banned = ['document', 'window', 'localStorage', 'sessionStorage', 'indexedDB', 'fetch', 'XMLHttpRequest', 'WebSocket', 'navigator', 'postMessage', 'globalThis', 'self'];
    for (const file of files) {
      const used = identifiersOf(file.text);
      expect(banned.filter((name) => used.has(name)), file.name).toEqual([]);
    }
  });

  it('the scanner sees string literals in template chunks too', () => {
    expect(literalsOf('const a = `x ${1} item`; const b = "run_command";')).toEqual(expect.arrayContaining(['run_command', ' item']));
  });
});
