/**
 * Session variables in the Logic tab (views.md section 3.4, 9): a session variable is declared like any other scope,
 * and where a rule or template names one that no declaration backs the studio offers to declare it. The pure half:
 * the declaration reader, the spellings, the scope and the lint that carries the "declare it" action.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import snapshot from '../../fixtures/golden/editor-demo-snapshot.json';
import { scopeFor } from '../src/lib/logic/scope';
import { project } from '../src/lib/logic/project';
import { lintTemplate, rewriteDeclaredRead } from '../src/lib/logic/template';
import { blockOf, type LogicBlock } from '../src/lib/logic/tree';
import {
  declarationEdits, declarationsOf, keyProblem, readPath, sessionDeclared, sessionId, sessionNameOf, sessionReadOf,
  VARIABLE_SCOPES,
} from '../src/lib/logic/variables';
import { readVocabulary } from '../src/lib/logic/vocabulary';

const vocabulary = readVocabulary(snapshot.describe.behavior)!;
const yaml = (text: string): LogicBlock => blockOf((parse(text) as Record<string, Record<string, unknown>>).r!);

const declared = declarationsOf('shop', 'variables/shop.yml', {
  coins: { scope: 'player', type: 'number' },
  tab: { scope: 'session', type: 'string', default: 'all', description: 'The selected tab' },
});

describe('declared session variables', () => {
  it('session is a scope of the variable type, as the registry self-description lists it', () => {
    const types = snapshot.describe.types as { name: string; parameters: { id: string; allowedValues?: string[] }[] }[];
    const scope = types.find((type) => type.name === 'variable')!.parameters.find((parameter) => parameter.id === 'scope')!;
    expect(scope.allowedValues).toEqual([...VARIABLE_SCOPES]);
    expect(scope.allowedValues).toContain('session');
  });

  it('reads a session declaration typed and defaulted like any other', () => {
    const tab = declared.find((variable) => variable.id === 'shop:tab')!;
    expect(tab).toMatchObject({ scope: 'session', type: 'string', default: 'all', description: 'The selected tab' });
    expect(readPath(tab)).toBe('vars.session.shop.tab');
  });

  it('writes a session declaration as the same one field edit', () => {
    expect(declarationEdits({ key: 'tab', scope: 'session', type: 'string', default: 'all' })).toEqual([
      { field: ['tab'], value: { scope: 'session', type: 'string', default: 'all' } },
    ]);
  });

  it('a session key follows the same rules as every key', () => {
    expect(keyProblem('tab', declared, 'shop')).toContain('already declared');
    expect(keyProblem('page', declared, 'shop')).toBeNull();
  });

  it('names a session variable by the spelling a step uses, and by the path an expression reads', () => {
    expect(sessionNameOf('session.tab')).toEqual({ pack: null, key: 'tab' });
    expect(sessionNameOf('session.shop.tab')).toEqual({ pack: 'shop', key: 'tab' });
    expect(sessionNameOf('player.coins')).toBeNull();
    expect(sessionNameOf('session')).toBeNull();
    expect(sessionNameOf('session.a.b.c')).toBeNull();
    expect(sessionReadOf('vars.session.shop.tab')).toEqual({ pack: 'shop', key: 'tab' });
    expect(sessionReadOf('vars.player.shop.coins')).toBeNull();
  });

  it('a bare key resolves in the editing pack, and only a session declaration backs it', () => {
    expect(sessionId({ pack: null, key: 'tab' }, 'shop')).toBe('shop:tab');
    expect(sessionDeclared({ pack: null, key: 'tab' }, 'shop', declared)).toBe(true);
    expect(sessionDeclared({ pack: 'shop', key: 'tab' }, 'other', declared)).toBe(true);
    expect(sessionDeclared({ pack: null, key: 'tab' }, 'other', declared)).toBe(false);
    expect(sessionDeclared({ pack: null, key: 'coins' }, 'shop', declared)).toBe(false); // a player variable is not a session one
  });
});

describe('the scope and the lint of a session read', () => {
  const block = yaml(`r:\n  behavior:\n    - on: gui.click\n      do:\n        - message: ""\n`);
  const graph = project(block);
  const node = graph.byId.get('behavior.0.do.0')!;
  const scopeOf = (known: boolean) => scopeFor(graph, block, node, vocabulary, declared, 'gui', [], known ? 'shop' : null);

  it('offers a declared session variable under vars.session.<pack>.<key>', () => {
    const paths = scopeOf(true).paths.map((entry) => entry.path);
    expect(paths).toContain('vars.session.shop.tab');
  });

  it('does not lint a session read until the declarations are known', () => {
    expect(scopeOf(false).sessions).toBeUndefined();
    expect(lintTemplate('${vars.session.shop.page}', scopeOf(false))).toEqual([]);
  });

  it('accepts a read a declaration backs', () => {
    expect(lintTemplate('Tab ${vars.session.shop.tab}', scopeOf(true))).toEqual([]);
  });

  it('flags an undeclared read in the own pack with the action that declares it', () => {
    const [problem] = lintTemplate('Page ${vars.session.shop.page}', scopeOf(true));
    expect(problem).toMatchObject({ severity: 'warn', declare: { key: 'page' } });
    expect(problem!.declare!.replace).toBeUndefined();
    expect(problem!.message).toContain('not a declared session variable');
  });

  it('flags a bare key, and says how the declared variable is then read', () => {
    const text = 'Page ${vars.session.page}';
    const [problem] = lintTemplate(text, scopeOf(true));
    expect(problem!.declare).toEqual({ key: 'page', name: 'vars.session.page', replace: 'vars.session.shop.page' });
    expect(problem!.message).toContain('vars.session.shop.page');
    expect(text.slice(problem!.from, problem!.to)).toBe('vars.session.page');
  });

  it('flags a read of another pack but offers no declaration for it', () => {
    const [problem] = lintTemplate('${vars.session.other.page}', scopeOf(true));
    expect(problem).toMatchObject({ severity: 'warn' });
    expect(problem!.declare).toBeUndefined();
  });

  it('does not take a session set_var name for a run-local variable', () => {
    const written = yaml(`r:\n  behavior:\n    - on: gui.click\n      do:\n        - set_var: {var: session.tab, value: x}\n        - set_var: {var: count, value: 1}\n        - message: ""\n`);
    const g = project(written);
    const paths = scopeFor(g, written, g.byId.get('behavior.0.do.2')!, vocabulary, declared, 'gui', [], 'shop').paths;
    expect(paths.filter((entry) => entry.origin === 'local').map((entry) => entry.path)).toEqual(['vars.count']);
  });
});

describe('the read a declaration rewrites', () => {
  const made = (scope: 'session' | 'player', key: string) => declarationsOf('shop', 'variables/shop.yml', { [key]: { scope, type: 'number' } })[0]!;
  const block = yaml(`r:
  behavior:
    - on: gui.click
      do:
        - message: ""
`);
  const graph = project(block);
  const scope = scopeFor(graph, block, graph.byId.get('behavior.0.do.0')!, vocabulary, declared, 'gui', [], 'shop');
  const problemIn = (text: string) => lintTemplate(text, scope)[0]!;

  it('spells the read as the declaration the form made, not the key that was offered', () => {
    const text = 'Page ${vars.session.page}';
    expect(rewriteDeclaredRead(text, problemIn(text), made('session', 'page'))).toBe('Page ${vars.session.shop.page}');
    expect(rewriteDeclaredRead(text, problemIn(text), made('session', 'pager'))).toBe('Page ${vars.session.shop.pager}');
  });

  it('spells the read in the scope the declaration ended in', () => {
    const text = 'Page ${vars.session.page}';
    expect(rewriteDeclaredRead(text, problemIn(text), made('player', 'page'))).toBe('Page ${vars.player.shop.page}');
  });

  it('finds the read again when the text changed under the stale offsets', () => {
    const text = 'Page ${vars.session.page}';
    const problem = problemIn(text);
    expect(rewriteDeclaredRead('>> ' + text, problem, made('session', 'page'))).toBe('>> Page ${vars.session.shop.page}');
  });

  it('takes the read nearest to where it was and never the middle of a longer path', () => {
    const text = '${vars.session.page} ${vars.session.page2} ${vars.session.page}';
    const problem = { ...problemIn('${vars.session.page}'), from: 44, to: 61 };
    expect(rewriteDeclaredRead(text, problem, made('session', 'page')))
      .toBe('${vars.session.page} ${vars.session.page2} ${vars.session.shop.page}');
  });

  it('leaves the text alone when the read is gone', () => {
    expect(rewriteDeclaredRead('nothing here', problemIn('${vars.session.page}'), made('session', 'page'))).toBeNull();
  });
});
