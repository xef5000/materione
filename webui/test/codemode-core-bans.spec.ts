/**
 * Ban tests for the SDK core (codemode.md section 3.1, 7, D5, D13, issue 339), kept by tests and not by review
 * goodwill:
 *
 *  - the core is registry-agnostic: no registry id as a string literal (the generator's ban, applied here), so
 *    adding a content type or an addon action to a registry needs no change in it. Two files are the
 *    deliberate exceptions and are checked on their own terms: `ops.ts` (the operation table: wire op names and
 *    capability names, nothing else) and `steps.ts` (the engine's step grammar, temporary until issue 355: only
 *    step words);
 *  - it reaches the server only through the table: no op name outside `ops.ts`, no call on the port but the
 *    four it declares, no apply (the table has no write op but `authoring.transaction`, and no read of it);
 *  - it is DOM-free, dependency-free and deterministic: imports only its own modules and the generator's pure
 *    ones, no browser, network, timer, Node or clock global, no `eval`, no `Math.random`.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { READS, WRITES } from '../src/lib/codemode/core/ops';
import { CAPABILITY_LOGIC, CAPABILITY_TRANSACTION } from '../src/lib/codemode/generate/capabilities';
import { readSnapshot, registryIds, surfaceNamed } from './helpers/codemode';

const CORE_DIR = fileURLToPath(new URL('../src/lib/codemode/core', import.meta.url));
const REPO = fileURLToPath(new URL('../..', import.meta.url));

/** Schema vocabulary the core may name although it coincides with a registry id; each is used and each has its reason. */
const SCHEMA_VOCABULARY: Record<string, string> = {
  number: 'the `typeof` word, which is also the id of an expression function',
};

function files(): { name: string; text: string }[] {
  return readdirSync(CORE_DIR)
    .filter((name) => name.endsWith('.ts'))
    .sort()
    .map((name) => ({ name, text: readFileSync(join(CORE_DIR, name), 'utf8') }));
}

function parse(text: string): ts.SourceFile {
  return ts.createSourceFile('source.ts', text, ts.ScriptTarget.ES2022, true);
}

function literalsOf(text: string): string[] {
  const found: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) found.push(node.text);
    if (ts.isTemplateHead(node) || ts.isTemplateMiddle(node) || ts.isTemplateTail(node)) found.push(node.text);
    ts.forEachChild(node, visit);
  };
  visit(parse(text));
  return found;
}

function importsOf(text: string): string[] {
  const found: string[] = [];
  parse(text).forEachChild((node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) found.push(node.moduleSpecifier.text);
    if (ts.isExportDeclaration(node) && node.moduleSpecifier !== undefined && ts.isStringLiteral(node.moduleSpecifier)) found.push(node.moduleSpecifier.text);
  });
  return found;
}

function identifiersOf(text: string): Set<string> {
  const found = new Set<string>();
  const visit = (node: ts.Node): void => {
    if (ts.isIdentifier(node)) found.add(node.text);
    ts.forEachChild(node, visit);
  };
  visit(parse(text));
  return found;
}

/** Every `.name` accessed on an identifier called `target`. */
function membersAccessedOn(text: string, target: string): Set<string> {
  const found = new Set<string>();
  const visit = (node: ts.Node): void => {
    if ((ts.isPropertyAccessExpression(node)) && ts.isIdentifier(node.expression) && node.expression.text === target) found.add(node.name.text);
    ts.forEachChild(node, visit);
  };
  visit(parse(text));
  return found;
}

/** The ops the plugin registers in this tree and the capabilities it advertises (the compat gate's own reading). */
function plugin(): { ops: Set<string>; capabilities: Set<string> } {
  const manifest = JSON.parse(readFileSync(join(REPO, 'fixtures', 'protocol', 'released', '0.1.0.json'), 'utf8')) as { ops: string[] };
  const ops = new Set<string>(manifest.ops);
  for (const file of listJava(join(REPO, 'web', 'src', 'main', 'java'))) {
    for (const match of readFileSync(file, 'utf8').matchAll(/dispatcher\s*\.\s*register\(\s*"([^"]+)"/g)) ops.add(match[1] ?? '');
  }
  const blob = readFileSync(join(REPO, 'web', 'src', 'main', 'java', 'dev', 'materione', 'web', 'protocol', 'SessionBlob.java'), 'utf8');
  const caps = /CURRENT_CAPABILITIES\s*=\s*List\.of\(([^)]*)\)/s.exec(blob);
  return { ops, capabilities: new Set([...(caps?.[1] ?? '').matchAll(/"([^"]+)"/g)].map((match) => match[1] ?? '')) };
}

function listJava(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? listJava(path) : entry.name.endsWith('.java') ? [path] : [];
  });
}

describe('the SDK core is registry-agnostic', () => {
  const all = files();
  const ids = registryIds();

  it('scans the core and the recorded registries, not nothing', () => {
    expect(all.map((file) => file.name)).toEqual(expect.arrayContaining(['session.ts', 'ops.ts', 'steps.ts', 'values.ts', 'diagnostics.ts', 'types.ts', 'index.ts']));
    expect(ids.size).toBeGreaterThan(150);
  });

  it('holds no registry id as a string literal (outside the two files checked below)', () => {
    const offenders = all
      .filter((file) => file.name !== 'ops.ts' && file.name !== 'steps.ts')
      .flatMap((file) => literalsOf(file.text).filter((value) => ids.has(value) && !(value in SCHEMA_VOCABULARY)).map((value) => `${file.name}: "${value}"`));
    expect(offenders).toEqual([]);
  });

  it('allows only schema vocabulary that coincides with an id, and each allowed word is really used', () => {
    for (const word of Object.keys(SCHEMA_VOCABULARY)) {
      expect(ids.has(word), `${word} is no longer an id: drop it from the allowance`).toBe(true);
      expect(all.some((file) => literalsOf(file.text).includes(word)), `${word} is unused: drop it from the allowance`).toBe(true);
    }
  });

  it('steps.ts names only the flow model\'s step words, which are step kinds of the registry\'s own list', () => {
    const steps = new Set(surfaceNamed('plugin-current').behavior.steps.map((step) => step.id));
    const text = all.find((file) => file.name === 'steps.ts')?.text ?? '';
    const literals = literalsOf(text).filter((value) => ids.has(value) && !(value in SCHEMA_VOCABULARY));
    expect(literals.length).toBeGreaterThan(0);
    for (const literal of literals) expect(steps.has(literal), `"${literal}" in steps.ts is a step kind`).toBe(true);
    // and no other file spells a step: the grammar lives in one place
    for (const file of all.filter((entry) => entry.name !== 'steps.ts' && entry.name !== 'ops.ts')) {
      for (const word of steps) expect(literalsOf(file.text), `${file.name} spells the step "${word}"`).not.toContain(word);
    }
  });

  it('knows no content type: a type name is never a literal (the writers, refs and handles come from the surface)', () => {
    const typeNames = new Set<string>();
    for (const type of readSnapshot('plugin-current').describe.types as { name: string; directory?: string }[]) {
      typeNames.add(type.name);
      if (type.directory !== undefined) typeNames.add(type.directory);
    }
    for (const file of all.filter((entry) => entry.name !== 'ops.ts')) {
      expect(literalsOf(file.text).filter((value) => typeNames.has(value)), file.name).toEqual([]);
    }
  });
});

describe('the ban sees a planted id', () => {
  it('flags a content type or an action id written as a literal, and not a word that is no id', () => {
    const ids = registryIds();
    const flagged = (code: string): string[] => literalsOf(code).filter((value) => ids.has(value) && !(value in SCHEMA_VOCABULARY));
    expect(flagged("const kind = 'rule';")).toEqual(['rule']);
    expect(flagged('const kind = "item"; const a = `take_money`;')).toEqual(['item', 'take_money']);
    // the core's own private words are not ids: a handle tag must not borrow a registry word
    expect(flagged("const tag = 'ruleHandle';")).toEqual([]);
  });
});

describe('the SDK core reaches the server only through its table', () => {
  const all = files();
  const { ops, capabilities } = plugin();

  it('the table names real ops and real capabilities, as lexical literals the compat gate reads', () => {
    const text = all.find((file) => file.name === 'ops.ts')?.text ?? '';
    const named = [...text.matchAll(/\bop:\s*'([^']+)'/g)].map((match) => match[1] ?? '');
    expect(named.length).toBe(READS.length + WRITES.length);
    for (const op of named) expect(ops.has(op), `${op} is an op the plugin registers`).toBe(true);
    for (const capability of [...text.matchAll(/\bcapability:\s*'([^']+)'/g)].map((match) => match[1] ?? '')) {
      expect(capabilities.has(capability), `${capability} is a capability the plugin advertises`).toBe(true);
    }
    // the capability words the table and the generator share are the same words
    expect(new Set(WRITES.map((entry) => entry.capability))).toEqual(new Set([CAPABILITY_TRANSACTION]));
    expect(READS.find((entry) => entry.sdk === 'rules.instantiate')?.capability).toBe(CAPABILITY_LOGIC);
  });

  it('no write op but the transaction, and the transaction is never read: the core cannot apply', () => {
    for (const entry of WRITES) expect(entry.op, entry.sdk).toBe('authoring.transaction');
    for (const entry of READS) {
      expect(entry.op, entry.sdk).not.toBe('authoring.transaction');
      expect(entry.op, entry.sdk).not.toMatch(/^(edit\.|authoring\.(commit|bulk|setFields|createResource|duplicateResource|detachTemplate|saveAsTemplate|saveModel)|assets\.(put|delete|move)|delivery\.publish|pipeline\.reload|admin\.settings\.put|gui\.(edit|customizeItem)|logic\.edit|runtime\.)/);
    }
  });

  it('no op name appears outside the table, and no request is built with a dry-run flag or a free-form text write', () => {
    const everyOp = [...ops];
    for (const file of all.filter((entry) => entry.name !== 'ops.ts')) {
      const literals = literalsOf(file.text);
      expect(literals.filter((value) => everyOp.includes(value)), `${file.name} names an op`).toEqual([]);
      expect(literals, `${file.name}`).not.toContain('dryRun');
      expect(literals, `${file.name}`).not.toContain('content');
      expect(literals, `${file.name}`).not.toContain('raw');
    }
  });

  it('the only things asked of the port are its four declared members', () => {
    const used = new Set<string>();
    for (const file of all) for (const member of membersAccessedOn(file.text, 'port')) used.add(member);
    expect([...used].sort()).toEqual(['done', 'log', 'read', 'stage']);
  });
});

describe('the SDK core is DOM-free, dependency-free and deterministic', () => {
  const all = files();

  it('imports only its own modules and the generator\'s pure ones', () => {
    const allowed = ['./types', './diagnostics', './ops', './steps', './values', './session', '../generate/capabilities', '../generate/context', '../generate/names', '../generate/surface'];
    for (const file of all) {
      for (const specifier of importsOf(file.text)) expect(allowed, `${file.name} imports ${specifier}`).toContain(specifier);
    }
  });

  it('touches no DOM, storage, network, timer, Node, clock or evaluation global', () => {
    const banned = [
      'document', 'window', 'localStorage', 'sessionStorage', 'indexedDB', 'fetch', 'XMLHttpRequest', 'WebSocket', 'navigator', 'postMessage',
      'globalThis', 'self', 'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'queueMicrotask', 'requestAnimationFrame',
      'process', 'require', 'module', 'Buffer', 'TextEncoder', 'TextDecoder', 'console', 'Date', 'performance', 'eval', 'Function', 'Promise', 'Worker', 'importScripts',
    ];
    for (const file of all) {
      const used = identifiersOf(file.text);
      // `require` is a property name in steps.ts (the step builder), not a module load
      const offenders = banned.filter((name) => used.has(name) && !(file.name === 'steps.ts' && name === 'require'));
      expect(offenders, file.name).toEqual([]);
    }
  });

  it('draws no randomness and reads no clock', () => {
    for (const file of all) {
      expect(membersAccessedOn(file.text, 'Math').has('random'), file.name).toBe(false);
      expect(file.text, file.name).not.toMatch(/\bnew Date\b|\bDate\.now\b/);
    }
  });
});
