/**
 * Ban tests for the code-mode host (codemode.md section 5.6, D1, D13; issue 346), kept by tests and not by review goodwill:
 *
 *  - no message from the sandbox or tool-call argument can produce a grant: nothing the sandbox, the SDK core, the type-check
 *    client or the generator loads can reach the host's approval code, and the agent-facing port has no member that approves,
 *    grants, applies or reverts;
 *  - a grant is added at one call site, behind a gesture, and an apply is sent at one call site, behind the policy;
 *  - the host holds session memory only (D1): no storage, no DOM;
 *  - it reaches the server through the two existing ops, by literal name, and the compat gate can read them.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const LIB = fileURLToPath(new URL('../src/lib', import.meta.url));
const HOST = join(LIB, 'codemode', 'host');

function sources(dir: string): { name: string; text: string }[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? sources(join(dir, entry.name)).map((file) => ({ ...file, name: `${entry.name}/${file.name}` }))
      : /\.(ts|svelte)$/.test(entry.name)
        ? [{ name: entry.name, text: readFileSync(join(dir, entry.name), 'utf8') }]
        : [],
  );
}

const host = (name: string): string => readFileSync(join(HOST, name), 'utf8');
const count = (text: string, pattern: RegExp): number => [...text.matchAll(pattern)].length;
const strip = (text: string): string => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

describe('the sandbox side cannot reach approval', () => {
  const sandboxSide = ['core', 'typecheck', 'generate'].flatMap((dir) =>
    sources(join(LIB, 'codemode', dir)).map((file) => ({ ...file, name: `${dir}/${file.name}` })),
  );

  it('loads nothing from the host', () => {
    for (const file of sandboxSide) {
      expect(file.text, file.name).not.toMatch(/from\s+['"][^'"]*\/host(\/|['"])/);
      expect(file.text, file.name).not.toMatch(/import\(\s*['"][^'"]*\/host/);
    }
  });

  it('names no approval vocabulary at all', () => {
    for (const file of sandboxSide) {
      expect(strip(file.text), file.name).not.toMatch(/ApprovalGesture|gestureFromEvent|GrantSet|spendGesture|isTrusted|\bapprove\w*\(|\bgrant\w*\(/);
    }
  });

  it('the surface the model is given has no approve, grant or apply member', () => {
    const declarations = readFileSync(join(LIB, 'codemode', 'generate', 'core.d.ts'), 'utf8');
    expect(declarations).not.toMatch(/\b(approve|grant|apply|revert)\w*\s*[(:]/i);
  });
});

describe('the agent-facing port', () => {
  it('has no member that approves, grants, applies, rejects or reverts', () => {
    const file = ts.createSourceFile('store.ts', host('store.ts'), ts.ScriptTarget.ES2022, true);
    let members: string[] | null = null;
    file.forEachChild((node) => {
      if (ts.isInterfaceDeclaration(node) && node.name.text === 'AgentPort') {
        members = node.members.map((member) => (member.name as ts.Identifier).text);
      }
    });
    expect(members).not.toBeNull();
    expect(members).toEqual(['propose', 'declarePlan', 'get', 'list', 'snapshot', 'subscribe']);
  });

  it('is built from those members only', () => {
    const text = strip(host('store.ts'));
    const body = /this\.agent = Object\.freeze\(\{([\s\S]*?)\}\);/.exec(text);
    expect(body).not.toBeNull();
    expect([...(body as RegExpExecArray)[1]!.matchAll(/^\s*(\w+):/gm)].map((match) => match[1])).toEqual([
      'propose',
      'declarePlan',
      'get',
      'list',
      'snapshot',
      'subscribe',
    ]);
  });
});

describe('one call site each for a grant and an apply', () => {
  const store = strip(host('store.ts'));

  it('the grant set is added to in exactly one place, behind a gesture', () => {
    expect(count(store, /\.grant\(/g)).toBe(1);
    expect(count(store, /new GrantSet\(/g)).toBe(2); // the session's, and the fresh one on dispose
    expect(count(strip(host('grants.ts')), /this\.granted\.add\(/g)).toBe(1);
    expect(strip(host('grants.ts'))).toMatch(/if \(!spendGesture\(gesture\)\) throw new GestureError/);
  });

  it('gestures are made from events in one place, and every spend is a user action', () => {
    for (const file of sources(LIB)) {
      if (file.name.startsWith('codemode/host/')) continue;
      expect(strip(file.text), file.name).not.toMatch(/gestureFromEvent\(|spendGesture\(|\bgrants?\./);
    }
    expect(count(store, /spendGesture\(/g)).toBe(1); // revert; approve's spend is inside GrantSet.grant
  });

  it('a real apply is sent in exactly one place, and only after the policy is re-evaluated there', () => {
    expect(count(store, /this\.transport\.transaction\(\{[^}]*dryRun:\s*false/g)).toBe(1);
    expect(count(store, /\.transaction\(/g)).toBe(2); // the dry run and that apply
    const send = /private async send\(entry: Entry\)[\s\S]*?\r?\n  }\r?\n/.exec(store);
    expect(send).not.toBeNull();
    const body = (send as RegExpExecArray)[0];
    expect(body.indexOf('decide(')).toBeGreaterThan(-1);
    expect(body.indexOf('decide(')).toBeLessThan(body.indexOf('dryRun: false'));
    expect(body).toMatch(/entry\.state !== 'waiting'/);
    // and nothing else calls the apply path without the queue
    expect(count(store, /this\.send\(/g)).toBe(3); // after the dry run, on approve, on apply
  });
});

describe('the host keeps session memory only (D1)', () => {
  it('uses no storage, no DOM and no timer', () => {
    for (const file of sources(HOST)) {
      expect(strip(file.text), file.name).not.toMatch(/localStorage|sessionStorage|indexedDB|\bwindow\.|\bglobalThis\b|\bdocument\.(querySelector|getElement|createElement|cookie|body|documentElement|addEventListener)|setTimeout|setInterval|navigator\./);
    }
  });
});

describe('the wire', () => {
  it('names the two existing ops by literal, in the transport only, and no other op', () => {
    for (const file of sources(HOST)) {
      const ops = [...file.text.matchAll(/\.call\(\s*(['"`])([^'"`]+)\1/g)].map((match) => match[2]);
      if (file.name === 'transport.ts') expect(ops.sort()).toEqual(['authoring.revertTransaction', 'authoring.transaction']);
      else expect(ops, file.name).toEqual([]);
      // no call the compat scanner would read as a dynamic op name
      expect(file.text, file.name).not.toMatch(/\.call\((?=\s*[^\s'"`)])/);
    }
  });

  it('checks the codemode capability before it calls', () => {
    expect(host('transport.ts')).toMatch(/hasCapability\(channel\.blob, CODEMODE_CAPABILITY\)/);
  });
});
